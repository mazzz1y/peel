package wtf.mazy.peel.model

import android.content.Context
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

        data class ImportData(
            val mode: ImportMode,
            val webApps: List<WebApp>,
            val globalSettings: WebAppSettings,
            val groups: List<WebAppGroup>,
            val proxies: List<Proxy>,
        ) : Action
    }

    private sealed interface Command {
        val done: CompletableDeferred<Unit>

        class Initialize(val context: Context, override val done: CompletableDeferred<Unit>) :
            Command

        class Apply(val action: Action, override val done: CompletableDeferred<Unit>) : Command
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
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private val _pushSubscriptionsChanged =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val pushSubscriptionsChanged: SharedFlow<Unit> = _pushSubscriptionsChanged.asSharedFlow()

    private val transientWebApps = mutableMapOf<String, WebApp>()

    init {
        actionScope.launch {
            for (command in commands) {
                runCatching {
                    when (command) {
                        is Command.Initialize -> openRepository(command.context)
                        is Command.Apply -> handle(command.action)
                    }
                }
                    .onFailure { command.done.completeExceptionally(it) }
                    .onSuccess { command.done.complete(Unit) }
            }
        }
    }

    suspend fun initialize(context: Context) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Initialize(context.applicationContext, done))
        done.await()
    }

    suspend fun awaitReady() {
        if (_isReady.value) return
        isReady.first { it }
    }

    suspend fun submit(action: Action) {
        awaitReady()
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Apply(action, done))
        done.await()
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

    fun sandboxOwner(contextId: String): SandboxOwner<*>? = webApp(contextId) ?: group(contextId)

    val proxies: List<Proxy>
        get() = state.value.proxies

    fun proxy(uuid: String): Proxy? = proxies.find { it.uuid == uuid }

    fun proxyDependents(uuid: String): Pair<List<WebApp>, List<WebAppGroup>> =
        webApps.filter { it.proxyUuid == uuid } to groups.filter { it.proxyUuid == uuid }

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

    suspend fun deleteWebApps(uuids: List<String>) = uuids.forEach { removeWebApp(it) }

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
        if (repository.loadGlobalSettings() == null) {
            repository.persistGlobalSettings(state.value.globalSettings)
        }
        reloadFromRepository()
        _isReady.value = true
    }

    private fun handle(action: Action) {
        when (action) {
            is Action.EnsureWebAppLoaded -> ensureLoaded(action)
            is Action.ReloadAll -> reloadFromRepository()
            is Action.PersistGlobalSettings ->
                repository.persistGlobalSettings(state.value.globalSettings)

            is Action.SetGlobalSettings -> {
                val value = action.value.withOwnSettings()
                repository.persistGlobalSettings(value)
                _state.value = state.value.copy(globalSettings = value)
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
                _pushSubscriptionsChanged.tryEmit(Unit)
            }

            is Action.RemovePushSubscription -> {
                repository.deletePushSubscription(action.instance)
                _pushSubscriptionsChanged.tryEmit(Unit)
            }

            is Action.ImportData -> when (action.mode) {
                ImportMode.REPLACE -> replaceAllData(action)
                ImportMode.MERGE -> mergeData(action)
            }
        }
    }

    private fun ensureLoaded(action: Action.EnsureWebAppLoaded) {
        val current = state.value
        if (!action.forceReload && current.webApps.any { it.uuid == action.uuid }) return
        val loadedWebApp = repository.loadWebApp(action.uuid) ?: return
        val loadedGroup = loadedWebApp.groupUuid?.let(repository::loadGroup)
        val loadedGlobal = repository.loadGlobalSettings()?.let(::ensureGlobalSettingsConcrete)
            ?: current.globalSettings
        _state.value = current.copy(
            webApps = current.webApps.replacingOrAppending(loadedWebApp) { it.uuid },
            groups = loadedGroup?.let { current.groups.replacingOrAppending(it) { it.uuid } }
                ?: current.groups,
            globalSettings = loadedGlobal,
        )
    }

    private fun handleAddWebApp(action: Action.AddWebApp) {
        val current = state.value
        val webApp = if (action.appendOrder) {
            action.webApp.copy(order = OrderAllocator(current).nextWebAppOrder(action.webApp.groupUuid))
        } else {
            action.webApp
        }
        repository.upsertWebApp(webApp)
        _state.value = current.copy(webApps = current.webApps + webApp)
    }

    private fun handleRemoveWebApp(action: Action.RemoveWebApp) {
        val current = state.value
        val webApp = current.webApps.find { it.uuid == action.uuid } ?: return
        repository.deleteWebApp(webApp.uuid)
        _state.value = current.copy(webApps = current.webApps - webApp)
        forgetWebApp(webApp)
        sideEffects.onShortcutsRemoved(listOf(webApp.uuid))
    }

    private fun handleReplaceWebApp(action: Action.ReplaceWebApp) {
        val current = state.value
        if (current.webApps.none { it.uuid == action.webApp.uuid }) return
        val webApp = action.webApp.withOwnSettings()
        repository.upsertWebApp(webApp)
        _state.value =
            current.copy(webApps = current.webApps.replacingOrAppending(webApp) { it.uuid })
    }

    private fun handleMoveWebAppsToGroup(action: Action.MoveWebAppsToGroup) {
        val uuids = action.uuids.toSet()
        val next = DataReducer.movingWebAppsToGroup(state.value, uuids, action.groupUuid)
        repository.upsertWebApps(next.webApps.filter { it.uuid in uuids })
        _state.value = next
    }

    private fun handleReorderWebApps(action: Action.ReorderWebApps) {
        val next = DataReducer.reorderingWebApps(state.value, action.orderedUuids)
        repository.upsertWebApps(next.webApps)
        _state.value = next
    }

    private fun handleAddGroup(action: Action.AddGroup) {
        val current = state.value
        val group = if (action.appendOrder) {
            action.group.copy(order = OrderAllocator(current).nextGroupOrder())
        } else {
            action.group
        }
        repository.upsertGroup(group)
        _state.value = current.copy(groups = current.groups + group)
    }

    private fun handleReplaceGroup(action: Action.ReplaceGroup) {
        val current = state.value
        if (current.groups.none { it.uuid == action.group.uuid }) return
        val group = action.group.withOwnSettings()
        repository.upsertGroup(group)
        _state.value = current.copy(groups = current.groups.replacingOrAppending(group) { it.uuid })
        sideEffects.onShortcutOwnerChanged(group)
    }

    private fun handleRemoveGroup(action: Action.RemoveGroup) {
        val current = state.value
        val groupUuid = action.group.uuid
        val appsInGroup = current.webApps.filter { it.groupUuid == groupUuid }
        val nextWebApps = if (action.ungroupApps) {
            val ungrouped = appsInGroup.map { it.copy(groupUuid = null) }
            repository.upsertWebApps(ungrouped)
            current.webApps.map { webApp -> ungrouped.find { it.uuid == webApp.uuid } ?: webApp }
        } else {
            appsInGroup.forEach { repository.deleteWebApp(it.uuid) }
            current.webApps - appsInGroup.toSet()
        }
        sideEffects.onSandboxRemoved(groupUuid)
        action.group.deleteIcon()
        if (!action.ungroupApps) appsInGroup.forEach(::forgetWebApp)
        repository.deleteGroup(groupUuid)
        _state.value = current.copy(
            webApps = nextWebApps,
            groups = current.groups.filterNot { it.uuid == groupUuid },
        )
        sideEffects.onShortcutsRemoved(
            buildList {
                add(groupUuid)
                if (!action.ungroupApps) addAll(appsInGroup.map { it.uuid })
            }
        )
    }

    private fun handleReorderGroups(action: Action.ReorderGroups) {
        val next = DataReducer.reorderingGroups(state.value, action.orderedUuids)
        repository.upsertGroups(next.groups)
        _state.value = next
    }

    private fun handleAddProxy(action: Action.AddProxy) {
        val current = state.value
        repository.upsertProxy(action.proxy)
        _state.value = current.copy(proxies = current.proxies + action.proxy)
    }

    private fun handleReplaceProxy(action: Action.ReplaceProxy) {
        val current = state.value
        if (current.proxies.none { it.uuid == action.proxy.uuid }) return
        repository.upsertProxy(action.proxy)
        _state.value =
            current.copy(proxies = current.proxies.replacingOrAppending(action.proxy) { it.uuid })
    }

    private fun handleRemoveProxy(action: Action.RemoveProxy) {
        val current = state.value
        if (current.proxies.none { it.uuid == action.uuid }) return
        repository.deleteProxy(action.uuid)

        val nextWebApps = current.webApps.map { webApp ->
            if (webApp.proxyUuid == action.uuid) webApp.copy(proxyUuid = null) else webApp
        }
        val nextGroups = current.groups.map { group ->
            if (group.proxyUuid == action.uuid) group.copy(proxyUuid = null) else group
        }
        val changedWebApps =
            nextWebApps.filterIndexed { i, webApp -> webApp !== current.webApps[i] }
        val changedGroups = nextGroups.filterIndexed { i, group -> group !== current.groups[i] }
        if (changedWebApps.isNotEmpty()) repository.upsertWebApps(changedWebApps)
        if (changedGroups.isNotEmpty()) repository.upsertGroups(changedGroups)

        _state.value = current.copy(
            webApps = nextWebApps,
            groups = nextGroups,
            proxies = current.proxies.filterNot { it.uuid == action.uuid },
        )
    }

    private fun replaceAllData(action: Action.ImportData) {
        val current = state.value
        val importedGroupUuids = action.groups.mapTo(mutableSetOf()) { it.uuid }
        current.groups.filter { it.uuid !in importedGroupUuids }
            .forEach { sideEffects.onSandboxRemoved(it.uuid) }

        val importedAppUuids = action.webApps.mapTo(mutableSetOf()) { it.uuid }
        val removedApps = current.webApps.filter { it.uuid !in importedAppUuids }
        removedApps.forEach(::forgetWebApp)
        if (removedApps.isNotEmpty()) sideEffects.onShortcutsRemoved(removedApps.map { it.uuid })

        repository.replaceAllWebApps(action.webApps)
        repository.replaceAllGroups(action.groups)
        repository.replaceAllProxies(action.proxies)
        repository.persistGlobalSettings(current.globalSettings.copy(settings = action.globalSettings))
        reloadFromRepository()
    }

    private fun mergeData(action: Action.ImportData) {
        val current = state.value
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
        val current = state.value
        val loadedWebApps = repository.loadAllWebApps()
        removeStaleShortcuts(current.webApps, loadedWebApps)
        _state.value = DataState(
            webApps = loadedWebApps,
            groups = repository.loadAllGroups(),
            globalSettings = repository.loadGlobalSettings()?.let(::ensureGlobalSettingsConcrete)
                ?: ensureGlobalSettingsConcrete(current.globalSettings),
            proxies = repository.loadAllProxies(),
        )
    }

    private fun forgetWebApp(webApp: WebApp) {
        if (webApp.isUseContainer) sideEffects.onSandboxRemoved(webApp.uuid)
        webApp.deleteIcon()
        deleteAppPrefs(App.appContext, webApp.uuid)
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

    private fun removeStaleShortcuts(oldWebApps: List<WebApp>, newWebApps: List<WebApp>) {
        val oldUrlByUuid = oldWebApps.associate { it.uuid to it.baseUrl }
        val staleUuids = newWebApps
            .filter { newApp ->
                val oldUrl = oldUrlByUuid[newApp.uuid]
                oldUrl != null && oldUrl != newApp.baseUrl
            }
            .map { it.uuid }
        if (staleUuids.isNotEmpty()) sideEffects.onShortcutsRemoved(staleUuids)
    }

    // WebAppSettings is still a mutable bag the settings screens keep editing after they save;
    // the stored value must not alias it or the next save compares equal and never emits.
    private fun WebApp.withOwnSettings(): WebApp = copy(settings = settings.deepCopy())

    private fun WebAppGroup.withOwnSettings(): WebAppGroup = copy(settings = settings.deepCopy())

    private fun <T> List<T>.replacingOrAppending(item: T, uuid: (T) -> String): List<T> {
        val index = indexOfFirst { uuid(it) == uuid(item) }
        if (index < 0) return this + item
        return toMutableList().apply { set(index, item) }
    }
}
