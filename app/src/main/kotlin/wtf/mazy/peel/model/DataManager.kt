package wtf.mazy.peel.model

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const
import java.util.concurrent.Executors

class DataManager private constructor() {

    private sealed interface Action {
        val done: CompletableDeferred<Unit>

        data class InitializeFull(
            override val done: CompletableDeferred<Unit>,
            val context: Context
        ) : Action

        data class InitializeSandbox(
            override val done: CompletableDeferred<Unit>,
            val context: Context
        ) : Action

        data class EnsureWebAppLoaded(
            override val done: CompletableDeferred<Unit>,
            val uuid: String,
            val forceReload: Boolean,
        ) : Action

        data class ReloadAll(override val done: CompletableDeferred<Unit>) : Action
        data class PersistDefaultSettings(override val done: CompletableDeferred<Unit>) : Action
        data class SetDefaultSettings(
            override val done: CompletableDeferred<Unit>,
            val value: WebApp
        ) : Action

        data class AddWebsite(override val done: CompletableDeferred<Unit>, val site: WebApp) :
            Action

        data class RemoveWebsite(override val done: CompletableDeferred<Unit>, val uuid: String) :
            Action

        data class ReplaceWebsite(override val done: CompletableDeferred<Unit>, val site: WebApp) :
            Action

        data class MoveWebAppsToGroup(
            override val done: CompletableDeferred<Unit>,
            val uuids: List<String>,
            val groupUuid: String?,
        ) : Action

        data class ReorderWebApps(
            override val done: CompletableDeferred<Unit>,
            val orderedUuids: List<String>
        ) : Action

        data class SoftDeleteWebApps(
            override val done: CompletableDeferred<Unit>,
            val uuids: List<String>
        ) : Action

        data class RestoreWebApps(
            override val done: CompletableDeferred<Unit>,
            val uuids: List<String>
        ) : Action

        data class ImportData(
            override val done: CompletableDeferred<Unit>,
            val importedWebApps: List<WebApp>,
            val globalSettings: WebAppSettings,
            val importedGroups: List<WebAppGroup>,
        ) : Action

        data class MergeData(
            override val done: CompletableDeferred<Unit>,
            val importedWebApps: List<WebApp>,
            val globalSettings: WebAppSettings,
            val importedGroups: List<WebAppGroup>,
        ) : Action

        data class AddGroup(override val done: CompletableDeferred<Unit>, val group: WebAppGroup) :
            Action

        data class ReplaceGroup(
            override val done: CompletableDeferred<Unit>,
            val group: WebAppGroup
        ) : Action

        data class RemoveGroup(
            override val done: CompletableDeferred<Unit>,
            val group: WebAppGroup,
            val ungroupApps: Boolean,
        ) : Action

        data class ReorderGroups(
            override val done: CompletableDeferred<Unit>,
            val orderedUuids: List<String>
        ) : Action
    }

    private val repository = DataRepository()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val actions = Channel<Action>(Channel.UNLIMITED)

    private val _state =
        MutableStateFlow(DataState(emptyList(), emptyList(), createDefaultSettings()))
    val state: StateFlow<DataState> = _state.asStateFlow()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    @Volatile
    private var currentState: DataState = _state.value

    init {
        scope.launch {
            for (action in actions) {
                runCatching { handle(action) }
                    .onFailure { action.done.completeExceptionally(it) }
                    .onSuccess { action.done.complete(Unit) }
            }
        }
    }

    val defaultSettings: WebApp
        get() = WebApp(currentState.defaultSettings)

    suspend fun initialize(context: Context) {
        enqueueAndAwait(Action.InitializeFull(CompletableDeferred(), context.applicationContext))
    }

    suspend fun initializeForSandbox(context: Context) {
        enqueueAndAwait(Action.InitializeSandbox(CompletableDeferred(), context.applicationContext))
    }

    suspend fun ensureWebAppLoaded(uuid: String, forceReload: Boolean = false) {
        awaitReady()
        enqueueAndAwait(Action.EnsureWebAppLoaded(CompletableDeferred(), uuid, forceReload))
    }

    suspend fun loadAppData() {
        awaitReady()
        enqueueAndAwait(Action.ReloadAll(CompletableDeferred()))
    }

    suspend fun awaitReady() {
        if (_isReady.value) return
        isReady.filter { it }.first()
    }

    suspend fun persistDefaultSettings() {
        awaitReady()
        enqueueAndAwait(Action.PersistDefaultSettings(CompletableDeferred()))
    }

    suspend fun setDefaultSettings(value: WebApp) {
        awaitReady()
        enqueueAndAwait(Action.SetDefaultSettings(CompletableDeferred(), WebApp(value)))
    }

    suspend fun addWebsite(newSite: WebApp) {
        awaitReady()
        enqueueAndAwait(Action.AddWebsite(CompletableDeferred(), WebApp(newSite)))
    }

    suspend fun removeWebApp(uuid: String) {
        awaitReady()
        enqueueAndAwait(Action.RemoveWebsite(CompletableDeferred(), uuid))
    }

    suspend fun cleanupAndRemoveWebApp(uuid: String, activity: Activity) {
        val webapp = currentState.websites.find { it.uuid == uuid }?.let { WebApp(it) } ?: return
        webapp.deleteShortcuts(activity)
        webapp.cleanupWebAppData(activity)
        removeWebApp(uuid)
    }

    suspend fun replaceWebApp(webapp: WebApp) {
        awaitReady()
        enqueueAndAwait(Action.ReplaceWebsite(CompletableDeferred(), WebApp(webapp)))
    }

    suspend fun moveWebAppsToGroup(uuids: List<String>, groupUuid: String?) {
        awaitReady()
        enqueueAndAwait(Action.MoveWebAppsToGroup(CompletableDeferred(), uuids, groupUuid))
    }

    suspend fun reorderWebApps(orderedUuids: List<String>) {
        awaitReady()
        enqueueAndAwait(Action.ReorderWebApps(CompletableDeferred(), orderedUuids))
    }

    suspend fun softDeleteWebApps(uuids: List<String>) {
        awaitReady()
        enqueueAndAwait(Action.SoftDeleteWebApps(CompletableDeferred(), uuids))
    }

    suspend fun restoreWebApps(uuids: List<String>) {
        awaitReady()
        enqueueAndAwait(Action.RestoreWebApps(CompletableDeferred(), uuids))
    }

    suspend fun commitDeleteWebApps(uuids: List<String>, activity: Activity) {
        uuids.forEach { cleanupAndRemoveWebApp(it, activity) }
    }

    suspend fun importData(
        importedWebApps: List<WebApp>,
        globalSettings: WebAppSettings,
        importedGroups: List<WebAppGroup> = emptyList(),
    ) {
        awaitReady()
        enqueueAndAwait(
            Action.ImportData(
                done = CompletableDeferred(),
                importedWebApps = importedWebApps.map { WebApp(it) },
                globalSettings = globalSettings.deepCopy(),
                importedGroups = importedGroups.map { WebAppGroup(it) },
            )
        )
    }

    suspend fun mergeData(
        importedWebApps: List<WebApp>,
        globalSettings: WebAppSettings,
        importedGroups: List<WebAppGroup> = emptyList(),
    ) {
        awaitReady()
        enqueueAndAwait(
            Action.MergeData(
                done = CompletableDeferred(),
                importedWebApps = importedWebApps.map { WebApp(it) },
                globalSettings = globalSettings.deepCopy(),
                importedGroups = importedGroups.map { WebAppGroup(it) },
            )
        )
    }

    fun getWebApp(uuid: String): WebApp? =
        currentState.websites.find { it.uuid == uuid }?.let { WebApp(it) }

    fun getWebsites(): List<WebApp> = currentState.websites.map { WebApp(it) }

    val activeWebsites: List<WebApp>
        get() = currentState.websites.filter { it.isActiveEntry }.sortedBy { it.order }
            .map { WebApp(it) }

    fun activeWebsitesForGroup(groupUuid: String?): List<WebApp> {
        return currentState.websites
            .filter { it.isActiveEntry && it.groupUuid == groupUuid }
            .sortedBy { it.order }
            .map { WebApp(it) }
    }

    val activeWebsitesCount: Int
        get() = currentState.websites.count { it.isActiveEntry }

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

    fun getGroups(): List<WebAppGroup> = currentState.groups.map { WebAppGroup(it) }

    val sortedGroups: List<WebAppGroup>
        get() = currentState.groups.sortedBy { it.order }.map { WebAppGroup(it) }

    fun getGroup(uuid: String): WebAppGroup? =
        currentState.groups.find { it.uuid == uuid }?.let { WebAppGroup(it) }

    suspend fun addGroup(group: WebAppGroup) {
        awaitReady()
        enqueueAndAwait(Action.AddGroup(CompletableDeferred(), WebAppGroup(group)))
    }

    suspend fun replaceGroup(group: WebAppGroup) {
        awaitReady()
        enqueueAndAwait(Action.ReplaceGroup(CompletableDeferred(), WebAppGroup(group)))
    }

    suspend fun removeGroup(group: WebAppGroup, ungroupApps: Boolean) {
        awaitReady()
        enqueueAndAwait(Action.RemoveGroup(CompletableDeferred(), WebAppGroup(group), ungroupApps))
    }

    suspend fun reorderGroups(orderedUuids: List<String>) {
        awaitReady()
        enqueueAndAwait(Action.ReorderGroups(CompletableDeferred(), orderedUuids))
    }

    fun resolveEffectiveSettings(webapp: WebApp): WebAppSettings {
        val globalSettings = currentState.defaultSettings.settings
        val groupSettings =
            webapp.groupUuid?.let { uuid -> currentState.groups.find { it.uuid == uuid }?.settings }
        return if (groupSettings != null) webapp.settings.getEffective(
            groupSettings,
            globalSettings
        )
        else webapp.settings.getEffective(globalSettings)
    }

    private fun handle(action: Action) {
        when (action) {
            is Action.InitializeFull -> {
                repository.initialize(action.context)
                if (repository.getGlobalSettings() == null) {
                    repository.persistGlobalSettings(currentState.defaultSettings)
                }
                reloadAll()
                _isReady.value = true
            }

            is Action.InitializeSandbox -> {
                repository.initialize(action.context)
                val loadedDefault =
                    repository.getGlobalSettings()?.let(::ensureDefaultSettingsConcrete)
                        ?: ensureDefaultSettingsConcrete(currentState.defaultSettings)
                updateState(
                    DataReducer.withDefaultSettings(
                        defaultSettings = loadedDefault,
                        emit = true,
                    )
                )
                _isReady.value = true
            }

            is Action.EnsureWebAppLoaded -> {
                if (!repository.isInitialized) return
                if (!action.forceReload && currentState.websites.any { it.uuid == action.uuid }) return
                val loadedWebApp = repository.getWebApp(action.uuid) ?: return
                val loadedGroup = loadedWebApp.groupUuid?.let(repository::getGroup)
                val loadedDefault =
                    repository.getGlobalSettings()?.let(::ensureDefaultSettingsConcrete)
                        ?: currentState.defaultSettings

                val nextWebsites = currentState.websites
                    .filterNot { it.uuid == loadedWebApp.uuid }
                    .toMutableList()
                    .apply { add(loadedWebApp) }
                val nextGroups = if (loadedGroup != null) {
                    currentState.groups
                        .filterNot { it.uuid == loadedGroup.uuid }
                        .toMutableList()
                        .apply { add(loadedGroup) }
                } else {
                    currentState.groups
                }

                updateState(
                    DataReducer.withLoadedData(
                        websites = nextWebsites,
                        groups = nextGroups,
                        defaultSettings = loadedDefault,
                        emit = true,
                    )
                )
            }

            is Action.ReloadAll -> reloadAll()

            is Action.PersistDefaultSettings -> {
                if (!repository.isInitialized) return
                repository.persistGlobalSettings(currentState.defaultSettings)
            }

            is Action.SetDefaultSettings -> {
                val nextDefault = WebApp(action.value)
                if (repository.isInitialized) repository.persistGlobalSettings(nextDefault)
                updateState(DataReducer.withDefaultSettings(nextDefault, emit = true))
            }

            is Action.AddWebsite -> {
                if (!repository.isInitialized) return
                repository.upsertWebApp(action.site)
                updateState(
                    DataReducer.withWebsites(
                        currentState.websites + WebApp(action.site),
                        emit = true
                    )
                )
            }

            is Action.RemoveWebsite -> {
                if (!repository.isInitialized) return
                if (currentState.websites.none { it.uuid == action.uuid }) return
                repository.deleteWebApp(action.uuid)
                updateState(
                    DataReducer.withWebsites(
                        currentState.websites.filterNot { it.uuid == action.uuid },
                        emit = true
                    )
                )
            }

            is Action.ReplaceWebsite -> {
                if (!repository.isInitialized) return
                if (currentState.websites.none { it.uuid == action.site.uuid }) return
                repository.upsertWebApp(action.site)
                updateState(DataReducer.replacingWebsite(currentState, action.site, emit = true))
            }

            is Action.MoveWebAppsToGroup -> {
                if (!repository.isInitialized) return
                val uuids = action.uuids.toSet()
                val mutation = DataReducer.movingWebsitesToGroup(
                    currentState,
                    uuids,
                    action.groupUuid,
                    emit = true
                )
                val nextWebsites = mutation.websites ?: return
                repository.upsertWebApps(nextWebsites.filter { it.uuid in uuids })
                updateState(mutation)
            }

            is Action.ReorderWebApps -> {
                if (!repository.isInitialized) return
                val mutation =
                    DataReducer.reorderingWebsites(currentState, action.orderedUuids, emit = true)
                val nextWebsites = mutation.websites ?: return
                repository.upsertWebApps(nextWebsites)
                updateState(mutation)
            }

            is Action.SoftDeleteWebApps -> {
                val uuids = action.uuids.toSet()
                val mutation = DataReducer.markingWebsitesActive(
                    currentState,
                    uuids,
                    isActive = false,
                    emit = true
                )
                val nextWebsites = mutation.websites ?: return
                if (repository.isInitialized) {
                    repository.upsertWebApps(nextWebsites.filter { it.uuid in uuids })
                }
                updateState(mutation)
            }

            is Action.RestoreWebApps -> {
                val uuids = action.uuids.toSet()
                val mutation = DataReducer.markingWebsitesActive(
                    currentState,
                    uuids,
                    isActive = true,
                    emit = true
                )
                val nextWebsites = mutation.websites ?: return
                if (repository.isInitialized) {
                    repository.upsertWebApps(nextWebsites.filter { it.uuid in uuids })
                }
                updateState(mutation)
            }

            is Action.ImportData -> {
                if (!repository.isInitialized) return
                val oldGroups = currentState.groups
                val oldWebsites = currentState.websites
                val importedGroupUuids = action.importedGroups.mapTo(mutableSetOf()) { it.uuid }
                oldGroups.filter { it.uuid !in importedGroupUuids }
                    .forEach { SandboxManager.clearSandboxData(App.appContext, it.uuid) }

                val importedAppUuids = action.importedWebApps.mapTo(mutableSetOf()) { it.uuid }
                oldWebsites.filter { it.uuid !in importedAppUuids }
                    .forEach {
                        SandboxManager.clearSandboxData(App.appContext, it.uuid)
                        deleteAppPrefs(App.appContext, it.uuid)
                    }

                val nextDefault = WebApp(currentState.defaultSettings).apply {
                    settings = action.globalSettings.deepCopy()
                }
                val nextWebsites = action.importedWebApps.map { WebApp(it) }
                val nextGroups = action.importedGroups.map { WebAppGroup(it) }

                repository.replaceAllWebApps(nextWebsites)
                repository.replaceAllGroups(nextGroups)
                repository.persistGlobalSettings(nextDefault)
                reloadAll()
            }

            is Action.MergeData -> {
                if (!repository.isInitialized) return
                val nextDefault = WebApp(currentState.defaultSettings).apply {
                    settings = action.globalSettings.deepCopy()
                }
                repository.persistGlobalSettings(nextDefault)
                repository.upsertWebApps(action.importedWebApps)
                repository.upsertGroups(action.importedGroups)
                reloadAll()
            }

            is Action.AddGroup -> {
                if (!repository.isInitialized) return
                repository.upsertGroup(action.group)
                updateState(
                    DataReducer.withGroups(
                        currentState.groups + WebAppGroup(action.group),
                        emit = true
                    )
                )
            }

            is Action.ReplaceGroup -> {
                if (!repository.isInitialized) return
                if (currentState.groups.none { it.uuid == action.group.uuid }) return
                repository.upsertGroup(action.group)
                updateState(DataReducer.replacingGroup(currentState, action.group, emit = true))
            }

            is Action.RemoveGroup -> {
                if (!repository.isInitialized) return
                val groupUuid = action.group.uuid
                val appsInGroup = currentState.websites.filter { it.groupUuid == groupUuid }
                val nextWebsites = if (action.ungroupApps) {
                    val updated = appsInGroup.map { WebApp(it).apply { this.groupUuid = null } }
                    repository.upsertWebApps(updated)
                    currentState.websites.map { site ->
                        updated.find { it.uuid == site.uuid } ?: WebApp(site)
                    }
                } else {
                    appsInGroup.forEach { repository.deleteWebApp(it.uuid) }
                    currentState.websites.filterNot { it.groupUuid == groupUuid }.map { WebApp(it) }
                }
                repository.deleteGroup(groupUuid)
                val nextGroups =
                    currentState.groups.filterNot { it.uuid == groupUuid }.map { WebAppGroup(it) }
                updateState(
                    DataReducer.withLoadedData(
                        websites = nextWebsites,
                        groups = nextGroups,
                        defaultSettings = currentState.defaultSettings,
                        emit = true,
                    )
                )
            }

            is Action.ReorderGroups -> {
                if (!repository.isInitialized) return
                val mutation =
                    DataReducer.reorderingGroups(currentState, action.orderedUuids, emit = true)
                val nextGroups = mutation.groups ?: return
                repository.replaceAllGroups(nextGroups)
                updateState(mutation)
            }
        }
    }

    private fun reloadAll() {
        if (!repository.isInitialized) return
        val oldWebsites = currentState.websites.map { WebApp(it) }
        val loadedWebsites = repository.getAllWebApps()
        removeStaleShortcuts(oldWebsites, loadedWebsites)
        val loadedGroups = repository.getAllGroups()
        val loadedDefault = repository.getGlobalSettings()?.let(::ensureDefaultSettingsConcrete)
            ?: ensureDefaultSettingsConcrete(currentState.defaultSettings)
        updateState(
            DataReducer.withLoadedData(
                websites = loadedWebsites,
                groups = loadedGroups,
                defaultSettings = loadedDefault,
                emit = true,
            )
        )
    }

    private fun updateState(mutation: DataReducer.StateMutation) {
        val next = DataReducer.apply(currentState, mutation)
        currentState = next
        if (mutation.emit) _state.value = next
    }

    private fun ensureDefaultSettingsConcrete(source: WebApp): WebApp {
        val settingsOwner = WebApp(source)
        val hadNulls =
            WebAppSettings.DEFAULTS.keys.any { settingsOwner.settings.getValue(it) == null }
        settingsOwner.settings.ensureAllConcrete()
        if (hadNulls && repository.isInitialized) {
            repository.persistGlobalSettings(settingsOwner)
        }
        return settingsOwner
    }

    private suspend fun enqueueAndAwait(action: Action) {
        actions.trySend(action).getOrThrow()
        action.done.await()
    }

    private fun createDefaultSettings(): WebApp {
        val webapp = WebApp("", Const.GLOBAL_WEBAPP_UUID)
        webapp.settings = WebAppSettings.createWithDefaults()
        return webapp
    }

    private fun removeStaleShortcuts(oldWebApps: List<WebApp>, newWebApps: List<WebApp>) {
        val oldUrlByUuid = oldWebApps.associate { it.uuid to it.baseUrl }
        val staleUuids = newWebApps
            .filter { newApp ->
                val oldUrl = oldUrlByUuid[newApp.uuid]
                oldUrl != null && oldUrl != newApp.baseUrl
            }
            .map { it.uuid }
        if (staleUuids.isNotEmpty()) {
            ShortcutIconUtils.deleteShortcuts(staleUuids, App.appContext)
        }
    }

    companion object {
        @JvmField
        val instance = DataManager()
    }
}
