package wtf.mazy.peel.model

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import wtf.mazy.peel.model.db.AppDatabase
import wtf.mazy.peel.model.db.LegacySharedPrefsMigration
import wtf.mazy.peel.model.db.WebAppDao
import wtf.mazy.peel.model.db.WebAppGroupDao
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.model.db.toEntity
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

class DataManager private constructor() {

    private var websites: MutableList<WebApp> = mutableListOf()
    private var groups: MutableList<WebAppGroup> = mutableListOf()
    private lateinit var dao: WebAppDao
    private lateinit var groupDao: WebAppGroupDao

    private var _defaultSettings: WebApp = createDefaultSettings()

    private val listeners = mutableListOf<DataEventListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingEvents = mutableSetOf<DataEvent>()

    fun addListener(listener: DataEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DataEventListener) {
        listeners.remove(listener)
    }

    private fun notify(event: DataEvent) {
        val wasEmpty = pendingEvents.isEmpty()
        pendingEvents.add(event)
        if (wasEmpty) {
            mainHandler.post {
                val batch = pendingEvents.toSet()
                pendingEvents.clear()
                for (e in batch) {
                    listeners.forEach { it.onDataEvent(e) }
                }
            }
        }
    }

    var defaultSettings: WebApp
        get() = WebApp(_defaultSettings)
        set(value) {
            _defaultSettings = WebApp(value)
            saveDefaultSettings()
            notify(DataEvent.SettingsChanged)
        }

    private fun createDefaultSettings(): WebApp {
        val webapp = WebApp("", Const.GLOBAL_WEBAPP_UUID)
        webapp.settings = WebAppSettings.createWithDefaults()
        return webapp
    }

    fun initialize(context: Context) {
        val db = AppDatabase.getInstance(context)
        dao = db.webAppDao()
        groupDao = db.webAppGroupDao()
        @Suppress("DEPRECATION")
        LegacySharedPrefsMigration.migrate(context, dao, db.sandboxSlotDao())
        if (dao.getGlobalSettings() == null) saveDefaultSettings()
        loadAppData()
    }

    fun loadAppData() {
        val newWebsites = dao.getAllWebApps().mapTo(mutableListOf()) { it.toDomain() }
        removeStaleShortcuts(websites, newWebsites)
        websites = newWebsites

        groups = groupDao.getAllGroups().mapTo(mutableListOf()) { it.toDomain() }

        val globalEntity = dao.getGlobalSettings()
        if (globalEntity != null) {
            _defaultSettings = globalEntity.toDomain()
            ensureDefaultSettingsAreConcrete()
        }
        notify(DataEvent.FullReload)
    }

    fun saveDefaultSettings() {
        dao.upsert(_defaultSettings.toEntity())
    }

    fun addWebsite(newSite: WebApp) {
        websites.add(WebApp(newSite))
        dao.upsert(newSite.toEntity())
        notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.ADDED))
    }

    fun removeWebApp(uuid: String) {
        val index = websites.indexOfFirst { it.uuid == uuid }
        if (index >= 0) {
            websites.removeAt(index)
            dao.deleteByUuid(uuid)
            notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.REMOVED))
        }
    }

    fun cleanupAndRemoveWebApp(uuid: String, activity: Activity) {
        val webapp = websites.find { it.uuid == uuid } ?: return
        webapp.deleteShortcuts(activity)
        webapp.cleanupWebAppData(activity)
        removeWebApp(uuid)
    }

    fun replaceWebApp(webapp: WebApp) {
        val index = websites.indexOfFirst { it.uuid == webapp.uuid }
        if (index >= 0) {
            websites[index] = WebApp(webapp)
            dao.upsert(webapp.toEntity())
            notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.UPDATED))
        }
    }

    fun moveWebAppsToGroup(uuids: List<String>, groupUuid: String?) {
        uuids.forEach { uuid ->
            val index = websites.indexOfFirst { it.uuid == uuid }
            if (index >= 0) {
                websites[index].groupUuid = groupUuid
                dao.upsert(websites[index].toEntity())
            }
        }
        notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.UPDATED))
    }

    fun reorderWebApps(orderedUuids: List<String>) {
        val byUuid = websites.associateBy { it.uuid }
        orderedUuids.forEachIndexed { index, uuid ->
            byUuid[uuid]?.order = index
        }
        dao.upsertAll(websites.map { it.toEntity() })
    }

    fun softDeleteWebApps(uuids: List<String>) {
        uuids.forEach { uuid ->
            websites.find { it.uuid == uuid }?.isActiveEntry = false
        }
        notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.REMOVED))
    }

    fun restoreWebApps(uuids: List<String>) {
        uuids.forEach { uuid ->
            websites.find { it.uuid == uuid }?.isActiveEntry = true
        }
        notify(DataEvent.WebAppsChanged(DataEvent.WebAppsChanged.Reason.ADDED))
    }

    fun commitDeleteWebApps(uuids: List<String>, activity: Activity) {
        uuids.forEach { uuid -> cleanupAndRemoveWebApp(uuid, activity) }
    }

    fun importData(
        importedWebApps: List<WebApp>,
        globalSettings: WebAppSettings,
        importedGroups: List<WebAppGroup> = emptyList(),
    ) {
        val importedGroupUuids = importedGroups.mapTo(mutableSetOf()) { it.uuid }
        groups
            .filter { it.uuid !in importedGroupUuids }
            .forEach { SandboxManager.wipeSandboxStorage(it.uuid) }

        val importedAppUuids = importedWebApps.mapTo(mutableSetOf()) { it.uuid }
        websites
            .filter { it.uuid !in importedAppUuids }
            .forEach { SandboxManager.wipeSandboxStorage(it.uuid) }

        websites = importedWebApps.toMutableList()
        groups = importedGroups.toMutableList()
        _defaultSettings.settings = globalSettings
        replaceAllWebAppData()
        saveDefaultSettings()
        saveGroupData()
        loadAppData()
    }

    fun mergeData(
        importedWebApps: List<WebApp>,
        globalSettings: WebAppSettings,
        importedGroups: List<WebAppGroup> = emptyList(),
    ) {
        _defaultSettings.settings = globalSettings
        saveDefaultSettings()
        dao.upsertAll(importedWebApps.map { it.toEntity() })
        groupDao.upsertAll(importedGroups.map { it.toEntity() })
        loadAppData()
    }

    fun getWebApp(uuid: String): WebApp? =
        websites.find { it.uuid == uuid }?.let { WebApp(it) }

    fun getWebsites(): List<WebApp> =
        websites.map { WebApp(it) }

    val activeWebsites: List<WebApp>
        get() = websites.filter { it.isActiveEntry }.sortedBy { it.order }.map { WebApp(it) }

    fun activeWebsitesForGroup(groupUuid: String?): List<WebApp> =
        websites
            .filter { it.isActiveEntry && it.groupUuid == groupUuid }
            .sortedBy { it.order }
            .map { WebApp(it) }

    val activeWebsitesCount: Int
        get() = websites.count { it.isActiveEntry }

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

    fun getGroups(): List<WebAppGroup> =
        groups.map { WebAppGroup(it) }

    val sortedGroups: List<WebAppGroup>
        get() = groups.sortedBy { it.order }.map { WebAppGroup(it) }

    fun getGroup(uuid: String): WebAppGroup? =
        groups.find { it.uuid == uuid }?.let { WebAppGroup(it) }

    fun addGroup(group: WebAppGroup) {
        groups.add(WebAppGroup(group))
        groupDao.upsert(group.toEntity())
        notify(DataEvent.GroupsChanged(DataEvent.GroupsChanged.Reason.ADDED))
    }

    fun replaceGroup(group: WebAppGroup) {
        val index = groups.indexOfFirst { it.uuid == group.uuid }
        if (index >= 0) {
            groups[index] = WebAppGroup(group)
            groupDao.upsert(group.toEntity())
            notify(DataEvent.GroupsChanged(DataEvent.GroupsChanged.Reason.UPDATED))
        }
    }

    fun removeGroup(group: WebAppGroup, ungroupApps: Boolean) {
        val appsInGroup = websites.filter { it.groupUuid == group.uuid }
        if (ungroupApps) {
            appsInGroup.forEach { webapp ->
                webapp.groupUuid = null
                dao.upsert(webapp.toEntity())
            }
        } else {
            appsInGroup.forEach { webapp ->
                websites.remove(webapp)
                dao.deleteByUuid(webapp.uuid)
            }
        }
        groups.removeAll { it.uuid == group.uuid }
        groupDao.deleteByUuid(group.uuid)
        notify(DataEvent.GroupsChanged(DataEvent.GroupsChanged.Reason.REMOVED))
    }

    fun reorderGroups(orderedUuids: List<String>) {
        val byUuid = groups.associateBy { it.uuid }
        orderedUuids.forEachIndexed { index, uuid ->
            byUuid[uuid]?.order = index
        }
        groupDao.replaceAll(groups.map { it.toEntity() })
    }

    fun resolveEffectiveSettings(webapp: WebApp): WebAppSettings {
        val globalSettings = _defaultSettings.settings
        val groupSettings = webapp.groupUuid?.let { uuid ->
            groups.find { it.uuid == uuid }?.settings
        }
        return if (groupSettings != null) {
            webapp.settings.getEffective(groupSettings, globalSettings)
        } else {
            webapp.settings.getEffective(globalSettings)
        }
    }

    private fun replaceAllWebAppData() {
        dao.replaceAllWebApps(websites.map { it.toEntity() })
    }

    private fun saveGroupData() {
        groupDao.replaceAll(groups.map { it.toEntity() })
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

    private fun ensureDefaultSettingsAreConcrete() {
        val hadNulls =
            WebAppSettings.DEFAULTS.keys.any { _defaultSettings.settings.getValue(it) == null }
        _defaultSettings.settings.ensureAllConcrete()
        if (hadNulls) saveDefaultSettings()
    }

    companion object {
        @JvmField
        val instance = DataManager()
    }
}
