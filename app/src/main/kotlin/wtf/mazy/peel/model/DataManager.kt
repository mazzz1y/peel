package wtf.mazy.peel.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.model.db.AppDatabase
import wtf.mazy.peel.model.db.PushSubscriptionEntity
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import java.util.concurrent.Executors

object DataManager {

    sealed interface Action {
        data class EnsureWebAppLoaded(val uuid: String, val forceReload: Boolean) : Action
        data object ReloadAll : Action
        data object PersistGlobalSettings : Action
        data class SetGlobalSettings(val value: WebApp) : Action

        data class AddWebApp(val webApp: WebApp, val appendOrder: Boolean) : Action
        data class RemoveWebApp(val uuid: String) : Action
        data class ReplaceWebApp(val webApp: WebApp) : Action
        data class MoveWebAppsToGroup(val uuids: List<String>, val groupUuid: String?) : Action
        data class ReorderWebApps(val orderedUuids: List<String>) : Action

        data class AddGroup(val group: WebAppGroup, val appendOrder: Boolean) : Action
        data class ReplaceGroup(val group: WebAppGroup) : Action
        data class RemoveGroup(val group: WebAppGroup, val ungroupApps: Boolean) : Action
        data class ReorderGroups(val orderedUuids: List<String>) : Action

        data class AddProxy(val proxy: Proxy) : Action
        data class ReplaceProxy(val proxy: Proxy) : Action
        data class RemoveProxy(val uuid: String) : Action

        data class UpsertPushSubscription(val entity: PushSubscriptionEntity) : Action
        data class RemovePushSubscription(val instance: String) : Action

        data object FactoryReset : Action

        data class ImportData(
            val mode: ImportMode,
            val webApps: List<WebApp>,
            val globalSettings: WebAppSettings,
            val groups: List<WebAppGroup>,
            val proxies: List<Proxy>,
        ) : Action
    }

    private sealed interface Command {
        class Initialize(val context: Context, val done: CompletableDeferred<Unit>) : Command

        class Apply(val action: Action, val done: CompletableDeferred<Boolean>) : Command
    }

    lateinit var sideEffects: DataSideEffects

    private lateinit var repository: DataRepository
    private val actionScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(
        DataState(emptyList(), emptyList(), createGlobalSettings(), emptyList())
    )
    val state: StateFlow<DataState> = _state.asStateFlow()
    private val _load = MutableStateFlow<DataLoad>(DataLoad.Loading)
    val load: StateFlow<DataLoad> = _load.asStateFlow()
    private val _pushSubscriptionsChanged =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val pushSubscriptionsChanged: SharedFlow<Unit> = _pushSubscriptionsChanged.asSharedFlow()

    private val transientWebApps = mutableMapOf<String, WebApp>()

    private var pendingState: DataState? = null
    private val pendingEffects = mutableListOf<() -> Unit>()

    private val working: DataState
        get() = pendingState ?: _state.value

    init {
        actionScope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Initialize -> {
                        _load.value = DataLoad.Loading
                        _load.value = runCatching { openRepository(command.context) }.fold(
                            onSuccess = { DataLoad.Ready },
                            onFailure = {
                                Log.e(TAG, "could not open data", it)
                                AppDatabase.close()
                                DataLoad.Failed(it)
                            },
                        )
                        command.done.complete(Unit)
                    }

                    is Command.Apply -> command.done.complete(apply(command.action).isSuccess)
                }
            }
        }
    }

    // One transaction per action; state and cleanup only follow a commit, so a failure leaves
    // both the database and what screens show exactly as they were.
    private fun apply(action: Action): Result<Unit> {
        val result = runCatching { repository.transaction { handle(action) } }
            .onFailure { Log.e(TAG, "${action::class.simpleName} failed; nothing stored", it) }
        val next = pendingState
        val effects = pendingEffects.toList()
        pendingState = null
        pendingEffects.clear()
        if (result.isFailure) return result
        next?.let { _state.value = it }
        effects.forEach { effect ->
            runCatching(effect)
                .onFailure { Log.w(TAG, "cleanup after ${action::class.simpleName} failed", it) }
        }
        return result
    }

    private fun publish(next: DataState) {
        pendingState = next
    }

    private fun afterCommit(effect: () -> Unit) {
        pendingEffects += effect
    }

    /** Opens the stored data; the outcome is published on [load]. Also used to retry. */
    suspend fun initialize(context: Context) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Initialize(context.applicationContext, done))
        done.await()
    }

    /** Suspends until the data is open; throws [DataUnavailableException] if it could not be. */
    suspend fun awaitReady() {
        val outcome = _load.first { it != DataLoad.Loading }
        if (outcome is DataLoad.Failed) throw DataUnavailableException(outcome.cause)
    }

    /**
     * Returns false when the action failed, which changes nothing and is logged; true when it
     * went through, including when it had nothing to change.
     */
    suspend fun submit(action: Action): Boolean {
        awaitReady()
        val done = CompletableDeferred<Boolean>()
        commands.send(Command.Apply(action, done))
        return done.await()
    }

    private suspend fun <T> query(block: DataRepository.() -> T): T {
        awaitReady()
        return withContext(Dispatchers.IO) { repository.block() }
    }

    val globalSettings: WebApp
        get() = state.value.globalSettings

    val webApps: List<WebApp>
        get() = state.value.webApps

    val sortedWebApps: List<WebApp>
        get() = webApps.sortedBy { it.order }

    val webAppCount: Int
        get() = webApps.size

    fun webAppsInGroup(groupUuid: String?): List<WebApp> =
        webApps.filter { it.groupUuid == groupUuid }.sortedBy { it.order }

    fun webApp(uuid: String): WebApp? =
        transientWebApps[uuid] ?: webApps.find { it.uuid == uuid }

    val groups: List<WebAppGroup>
        get() = state.value.groups

    val sortedGroups: List<WebAppGroup>
        get() = groups.sortedBy { it.order }

    fun group(uuid: String): WebAppGroup? = groups.find { it.uuid == uuid }

    fun sandboxOwner(contextId: String): SandboxOwner<*, *>? = webApp(contextId) ?: group(contextId)

    val proxies: List<Proxy>
        get() = state.value.proxies

    fun proxy(uuid: String): Proxy? = proxies.find { it.uuid == uuid }

    fun proxyDependents(uuid: String): Pair<List<WebApp>, List<WebAppGroup>> {
        val via = ProxyRoute.Via(uuid)
        return webApps.filter { it.isolation.proxyOrNull == via } to
                groups.filter { it.isolation.proxyOrNull == via }
    }

    fun effectiveSandbox(webApp: WebApp): EffectiveSandbox =
        effectiveSandbox(webApp, webApp.groupUuid?.let(::group))

    fun effectiveSandbox(contextId: String): EffectiveSandbox? =
        webApp(contextId)?.let(::effectiveSandbox) ?: group(contextId)?.let(::effectiveSandbox)

    fun effectiveSettings(webApp: WebApp): EffectiveSettings {
        val current = state.value
        val groupSettings = webApp.groupUuid
            ?.let { uuid -> current.groups.find { it.uuid == uuid } }
            ?.settings
        return if (groupSettings != null) {
            webApp.settings.effective(groupSettings, current.globalSettings.settings)
        } else {
            webApp.settings.effective(current.globalSettings.settings)
        }
    }

    fun effectiveSettings(group: WebAppGroup): EffectiveSettings =
        group.settings.effective(state.value.globalSettings.settings)

    val globalEffectiveSettings: EffectiveSettings
        get() = state.value.globalSettings.settings.effective()

    val transientWebAppList: List<WebApp>
        get() = transientWebApps.values.toList()

    fun registerTransientWebApp(
        baseUrl: String,
        title: String,
        privateSession: Boolean = false,
    ): String {
        val webApp = WebApp(baseUrl = baseUrl, title = title, isPrivateSession = privateSession)
        transientWebApps[webApp.uuid] = webApp
        return webApp.uuid
    }

    fun isTransientWebApp(uuid: String): Boolean = transientWebApps.containsKey(uuid)

    fun removeTransientWebApp(uuid: String) {
        transientWebApps.remove(uuid)
    }

    suspend fun ensureWebAppLoaded(uuid: String, forceReload: Boolean = false) =
        submit(Action.EnsureWebAppLoaded(uuid, forceReload))

    suspend fun reloadAll() = submit(Action.ReloadAll)

    suspend fun persistGlobalSettings() = submit(Action.PersistGlobalSettings)

    suspend fun setGlobalSettings(value: WebApp) = submit(Action.SetGlobalSettings(value))

    suspend fun addWebApp(webApp: WebApp, appendOrder: Boolean = false) =
        submit(Action.AddWebApp(webApp, appendOrder))

    suspend fun removeWebApp(uuid: String) = submit(Action.RemoveWebApp(uuid))

    suspend fun deleteWebApps(uuids: List<String>) {
        uuids.forEach { removeWebApp(it) }
    }

    suspend fun replaceWebApp(webApp: WebApp) = submit(Action.ReplaceWebApp(webApp))

    suspend fun moveWebAppsToGroup(uuids: List<String>, groupUuid: String?) =
        submit(Action.MoveWebAppsToGroup(uuids, groupUuid))

    suspend fun reorderWebApps(orderedUuids: List<String>) =
        submit(Action.ReorderWebApps(orderedUuids))

    suspend fun addGroup(group: WebAppGroup, appendOrder: Boolean = false) =
        submit(Action.AddGroup(group, appendOrder))

    suspend fun replaceGroup(group: WebAppGroup) = submit(Action.ReplaceGroup(group))

    suspend fun removeGroup(group: WebAppGroup, ungroupApps: Boolean) =
        submit(Action.RemoveGroup(group, ungroupApps))

    suspend fun reorderGroups(orderedUuids: List<String>) =
        submit(Action.ReorderGroups(orderedUuids))

    suspend fun addProxy(proxy: Proxy) = submit(Action.AddProxy(proxy))

    suspend fun replaceProxy(proxy: Proxy) = submit(Action.ReplaceProxy(proxy))

    suspend fun removeProxy(uuid: String) = submit(Action.RemoveProxy(uuid))

    suspend fun upsertPushSubscription(entity: PushSubscriptionEntity) =
        submit(Action.UpsertPushSubscription(entity))

    suspend fun removePushSubscription(instance: String) =
        submit(Action.RemovePushSubscription(instance))

    suspend fun importData(
        mode: ImportMode,
        webApps: List<WebApp>,
        globalSettings: WebAppSettings,
        groups: List<WebAppGroup> = emptyList(),
        proxies: List<Proxy> = emptyList(),
    ) = submit(Action.ImportData(mode, webApps, globalSettings, groups, proxies))

    suspend fun factoryReset() = submit(Action.FactoryReset)

    suspend fun queryAllWebApps(): List<WebApp> = query { loadAllWebApps() }

    suspend fun queryGroup(uuid: String): WebAppGroup? = query { loadGroup(uuid) }

    suspend fun pushSubscriptions(): List<PushSubscriptionEntity> =
        query { loadAllPushSubscriptions() }

    suspend fun pushSubscription(instance: String): PushSubscriptionEntity? =
        query { loadPushSubscription(instance) }

    suspend fun pushSubscriptionByScope(scope: String): PushSubscriptionEntity? =
        query { loadPushSubscriptionByScope(scope) }

    suspend fun pushSubscriptionsForContext(contextId: String): List<PushSubscriptionEntity> =
        query { loadPushSubscriptionsForContext(contextId) }

    private fun openRepository(context: Context) {
        repository = DataRepository(AppDatabase.getInstance(context))
        apply(Action.ReloadAll).getOrThrow()
    }

    private fun handle(action: Action) {
        when (action) {
            is Action.EnsureWebAppLoaded -> ensureLoaded(action)
            is Action.ReloadAll -> {
                if (repository.loadGlobalSettings() == null) {
                    repository.persistGlobalSettings(working.globalSettings)
                }
                reloadFromRepository()
            }
            is Action.PersistGlobalSettings ->
                repository.persistGlobalSettings(working.globalSettings)

            is Action.SetGlobalSettings -> {
                val value = action.value.withOwnSettings()
                repository.persistGlobalSettings(value)
                publish(working.copy(globalSettings = value))
            }

            is Action.AddWebApp -> handleAddWebApp(action)
            is Action.RemoveWebApp -> handleRemoveWebApp(action)
            is Action.ReplaceWebApp -> handleReplaceWebApp(action)
            is Action.MoveWebAppsToGroup -> handleMoveWebAppsToGroup(action)
            is Action.ReorderWebApps -> handleReorderWebApps(action)

            is Action.AddGroup -> handleAddGroup(action)
            is Action.ReplaceGroup -> handleReplaceGroup(action)
            is Action.RemoveGroup -> handleRemoveGroup(action)
            is Action.ReorderGroups -> handleReorderGroups(action)

            is Action.AddProxy -> handleAddProxy(action)
            is Action.ReplaceProxy -> handleReplaceProxy(action)
            is Action.RemoveProxy -> handleRemoveProxy(action)

            is Action.UpsertPushSubscription -> {
                repository.upsertPushSubscription(action.entity)
                afterCommit { _pushSubscriptionsChanged.tryEmit(Unit) }
            }

            is Action.RemovePushSubscription -> {
                repository.deletePushSubscription(action.instance)
                afterCommit { _pushSubscriptionsChanged.tryEmit(Unit) }
            }

            is Action.FactoryReset -> handleFactoryReset()

            is Action.ImportData -> {
                val imported = action.copy(
                    webApps = action.webApps.filterNot { it.uuid == Const.GLOBAL_WEBAPP_UUID },
                )
                when (action.mode) {
                    ImportMode.REPLACE -> replaceAllData(imported)
                    ImportMode.MERGE -> mergeData(imported)
                }
            }
        }
    }

    private fun ensureLoaded(action: Action.EnsureWebAppLoaded) {
        if (action.uuid == Const.GLOBAL_WEBAPP_UUID) return
        val current = working
        if (!action.forceReload && current.webApps.any { it.uuid == action.uuid }) return
        val proxyUuids = current.proxyUuids
        val loadedWebApp = repository.loadWebApp(action.uuid)
            ?.withExistingProxy(proxyUuids)
            ?: return
        val loadedGroup = loadedWebApp.groupUuid?.let(repository::loadGroup)
            ?.withExistingProxy(proxyUuids)
        val loadedGlobal = repository.loadGlobalSettings()?.let(::ensureGlobalSettingsConcrete)
            ?: current.globalSettings
        publish(current.copy(
            webApps = current.webApps.replacingOrAppending(loadedWebApp) { it.uuid },
            groups = loadedGroup?.let { group -> current.groups.replacingOrAppending(group) { it.uuid } }
                ?: current.groups,
            globalSettings = loadedGlobal,
        ))
    }

    private fun handleAddWebApp(action: Action.AddWebApp) {
        if (action.webApp.uuid == Const.GLOBAL_WEBAPP_UUID) return
        val current = working
        val added = action.webApp.withExistingProxy(current.proxyUuids)
        val webApp = if (action.appendOrder) {
            added.copy(order = OrderAllocator(current).nextWebAppOrder(added.groupUuid))
        } else {
            added
        }
        repository.upsertWebApp(webApp)
        publish(current.copy(webApps = current.webApps + webApp))
    }

    private fun handleRemoveWebApp(action: Action.RemoveWebApp) {
        val current = working
        val webApp = current.webApps.find { it.uuid == action.uuid } ?: return
        repository.deleteWebApp(webApp.uuid)
        publish(current.copy(webApps = current.webApps - webApp))
        forgetWebApps(listOf(webApp))
    }

    private fun handleReplaceWebApp(action: Action.ReplaceWebApp) {
        if (action.webApp.uuid == Const.GLOBAL_WEBAPP_UUID) return
        val current = working
        val stored = current.webApps.find { it.uuid == action.webApp.uuid } ?: return
        val replacement = action.webApp.withOwnSettings().withExistingProxy(current.proxyUuids)
        val webApp = if (replacement.groupUuid == stored.groupUuid) {
            replacement.copy(order = stored.order)
        } else {
            replacement.copy(order = OrderAllocator(current).nextWebAppOrder(replacement.groupUuid))
        }
        repository.upsertWebApp(webApp)
        publish(current.copy(webApps = current.webApps.replacingOrAppending(webApp) { it.uuid }))
    }

    private fun handleMoveWebAppsToGroup(action: Action.MoveWebAppsToGroup) {
        val uuids = action.uuids.toSet()
        val next = DataReducer.movingWebAppsToGroup(working, uuids, action.groupUuid)
        repository.upsertWebApps(next.webApps.filter { it.uuid in uuids })
        publish(next)
    }

    private fun handleReorderWebApps(action: Action.ReorderWebApps) {
        val next = DataReducer.reorderingWebApps(working, action.orderedUuids)
        repository.upsertWebApps(next.webApps)
        publish(next)
    }

    private fun handleAddGroup(action: Action.AddGroup) {
        val current = working
        val added = action.group.withExistingProxy(current.proxyUuids)
        val group = if (action.appendOrder) {
            added.copy(order = OrderAllocator(current).nextGroupOrder())
        } else {
            added
        }
        repository.upsertGroup(group)
        publish(current.copy(groups = current.groups + group))
    }

    private fun handleReplaceGroup(action: Action.ReplaceGroup) {
        val current = working
        if (current.groups.none { it.uuid == action.group.uuid }) return
        val group = action.group.withOwnSettings().withExistingProxy(current.proxyUuids)
        repository.upsertGroup(group)
        publish(current.copy(groups = current.groups.replacingOrAppending(group) { it.uuid }))
        afterCommit { sideEffects.onShortcutOwnerChanged(group) }
    }

    private fun handleRemoveGroup(action: Action.RemoveGroup) {
        val current = working
        val groupUuid = action.group.uuid
        val appsInGroup = current.webApps.filter { it.groupUuid == groupUuid }
        val withoutApps = if (action.ungroupApps) {
            val moved = appsInGroup.mapTo(mutableSetOf()) { it.uuid }
            DataReducer.movingWebAppsToGroup(current, moved, groupUuid = null).also { next ->
                repository.upsertWebApps(next.webApps.filter { it.uuid in moved })
            }
        } else {
            appsInGroup.forEach { repository.deleteWebApp(it.uuid) }
            forgetWebApps(appsInGroup)
            current.copy(webApps = current.webApps - appsInGroup.toSet())
        }
        repository.deleteGroup(groupUuid)
        publish(withoutApps.copy(groups = withoutApps.groups.filterNot { it.uuid == groupUuid }))
        forgetGroups(listOf(action.group))
    }

    private fun handleReorderGroups(action: Action.ReorderGroups) {
        val next = DataReducer.reorderingGroups(working, action.orderedUuids)
        repository.upsertGroups(next.groups)
        publish(next)
    }

    private fun handleAddProxy(action: Action.AddProxy) {
        val current = working
        repository.upsertProxy(action.proxy)
        publish(current.copy(proxies = current.proxies + action.proxy))
    }

    private fun handleReplaceProxy(action: Action.ReplaceProxy) {
        val current = working
        if (current.proxies.none { it.uuid == action.proxy.uuid }) return
        repository.upsertProxy(action.proxy)
        publish(current.copy(proxies = current.proxies.replacingOrAppending(action.proxy) { it.uuid }))
    }

    private fun handleRemoveProxy(action: Action.RemoveProxy) {
        val current = working
        if (current.proxies.none { it.uuid == action.uuid }) return
        repository.deleteProxy(action.uuid)
        publish(
            withExistingProxies(
                current.copy(proxies = current.proxies.filterNot { it.uuid == action.uuid })
            )
        )
    }

    private fun replaceAllData(action: Action.ImportData) {
        val current = working
        val importedGroupUuids = action.groups.mapTo(mutableSetOf()) { it.uuid }
        val importedAppUuids = action.webApps.mapTo(mutableSetOf()) { it.uuid }
        repository.replaceAllWebApps(action.webApps)
        repository.replaceAllGroups(action.groups)
        repository.replaceAllProxies(action.proxies)
        repository.persistGlobalSettings(current.globalSettings.copy(settings = action.globalSettings))
        reloadFromRepository()
        forgetWebApps(current.webApps.filter { it.uuid !in importedAppUuids })
        forgetGroups(current.groups.filter { it.uuid !in importedGroupUuids })
    }

    private fun handleFactoryReset() = replaceAllData(
        Action.ImportData(
            mode = ImportMode.REPLACE,
            webApps = emptyList(),
            globalSettings = WebAppSettings.createWithDefaults(),
            groups = emptyList(),
            proxies = emptyList(),
        ),
    )

    private fun mergeData(action: Action.ImportData) {
        val current = working
        val localApps = current.webApps.associateBy { it.uuid }
        val localGroups = current.groups.associateBy { it.uuid }
        val allocator = OrderAllocator(current)
        val mergedWebApps = action.webApps.map { webApp ->
            val localOrder = localApps[webApp.uuid]
                ?.takeIf { it.groupUuid == webApp.groupUuid }
                ?.order
            webApp.copy(order = localOrder ?: allocator.nextWebAppOrder(webApp.groupUuid))
        }
        val mergedGroups = action.groups.map { group ->
            group.copy(order = localGroups[group.uuid]?.order ?: allocator.nextGroupOrder())
        }
        repository.persistGlobalSettings(current.globalSettings.copy(settings = action.globalSettings))
        repository.upsertWebApps(mergedWebApps)
        repository.upsertGroups(mergedGroups)
        repository.upsertProxies(action.proxies)
        reloadFromRepository()
    }

    private fun reloadFromRepository() {
        val current = working
        val loadedWebApps = repository.loadAllWebApps()
        val staleShortcuts = staleShortcutUuids(current.webApps, loadedWebApps)
        if (staleShortcuts.isNotEmpty()) {
            afterCommit { sideEffects.onShortcutsRemoved(staleShortcuts) }
        }
        publish(
            withExistingProxies(
                DataState(
                    webApps = loadedWebApps,
                    groups = repository.loadAllGroups(),
                    globalSettings = repository.loadGlobalSettings()?.let(::ensureGlobalSettingsConcrete)
                        ?: ensureGlobalSettingsConcrete(current.globalSettings),
                    proxies = repository.loadAllProxies(),
                )
            )
        )
    }

    private fun withExistingProxies(state: DataState): DataState {
        val next = DataReducer.withExistingProxies(state)
        val changedWebApps = next.webApps.filterIndexed { i, webApp -> webApp !== state.webApps[i] }
        val changedGroups = next.groups.filterIndexed { i, group -> group !== state.groups[i] }
        if (changedWebApps.isNotEmpty()) repository.upsertWebApps(changedWebApps)
        if (changedGroups.isNotEmpty()) repository.upsertGroups(changedGroups)
        return next
    }

    private fun forgetWebApps(webApps: List<WebApp>) {
        if (webApps.isEmpty()) return
        afterCommit { sideEffects.onWebAppsRemoved(webApps.mapTo(mutableSetOf()) { it.uuid }) }
        webApps.forEach { webApp ->
            if (webApp.isolation.isSandboxed) afterCommit { sideEffects.onSandboxRemoved(webApp.uuid) }
            afterCommit { webApp.deleteIcon() }
            afterCommit { deleteAppPrefs(App.appContext, webApp.uuid) }
        }
        afterCommit { sideEffects.onShortcutsRemoved(webApps.map { it.uuid }) }
    }

    private fun forgetGroups(groups: List<WebAppGroup>) {
        groups.forEach { group ->
            afterCommit { sideEffects.onSandboxRemoved(group.uuid) }
            afterCommit { group.deleteIcon() }
        }
        if (groups.isNotEmpty()) afterCommit { sideEffects.onShortcutsRemoved(groups.map { it.uuid }) }
    }

    private fun ensureGlobalSettingsConcrete(source: WebApp): WebApp {
        val settings = source.settings.deepCopy()
        val hadNulls = WebAppSettings.DEFAULTS.keys.any { settings.getValue(it) == null }
        if (!hadNulls) return source
        settings.ensureAllConcrete()
        val concrete = source.copy(settings = settings)
        repository.persistGlobalSettings(concrete)
        return concrete
    }

    private fun createGlobalSettings(): WebApp =
        WebApp(
            baseUrl = "",
            uuid = Const.GLOBAL_WEBAPP_UUID,
            settings = WebAppSettings.createWithDefaults(),
        )

    private fun staleShortcutUuids(oldWebApps: List<WebApp>, newWebApps: List<WebApp>): List<String> {
        val oldUrlByUuid = oldWebApps.associate { it.uuid to it.baseUrl }
        return newWebApps
            .filter { newApp ->
                val oldUrl = oldUrlByUuid[newApp.uuid]
                oldUrl != null && oldUrl != newApp.baseUrl
            }
            .map { it.uuid }
    }

    // WebAppSettings is still a mutable bag the settings screens keep editing after they save;
    // the stored value must not alias it or the next save compares equal and never emits.
    private fun WebApp.withOwnSettings(): WebApp = copy(settings = settings.deepCopy())

    private fun WebAppGroup.withOwnSettings(): WebAppGroup = copy(settings = settings.deepCopy())

    private const val TAG = "DataManager"

    private fun <T> List<T>.replacingOrAppending(item: T, uuid: (T) -> String): List<T> {
        val index = indexOfFirst { uuid(it) == uuid(item) }
        if (index < 0) return this + item
        return toMutableList().apply { set(index, item) }
    }
}
