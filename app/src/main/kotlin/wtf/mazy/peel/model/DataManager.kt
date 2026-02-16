package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.model.db.*
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

class DataManager private constructor() {
    private var websites: MutableList<WebApp> = mutableListOf()
    private var groups: MutableList<WebAppGroup> = mutableListOf()
    private lateinit var dao: WebAppDao
    private lateinit var groupDao: WebAppGroupDao

    private var _defaultSettings: WebApp = createDefaultSettings()

    var defaultSettings: WebApp
        get() = _defaultSettings
        set(value) {
            _defaultSettings = value
            saveDefaultSettings()
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
    }

    fun saveWebAppData() {
        dao.replaceAllWebApps(websites.map { it.toEntity() })
    }

    fun saveDefaultSettings() {
        dao.upsert(_defaultSettings.toEntity())
    }

    fun addWebsite(newSite: WebApp) {
        websites.add(newSite)
        dao.upsert(newSite.toEntity())
    }

    fun removeWebApp(webapp: WebApp) {
        websites.remove(webapp)
        dao.deleteByUuid(webapp.uuid)
    }

    fun importData(
        importedWebApps: List<WebApp>,
        globalSettings: WebAppSettings,
        importedGroups: List<WebAppGroup> = emptyList(),
    ) {
        val importedGroupUuids = importedGroups.mapTo(mutableSetOf()) { it.uuid }
        groups.filter { it.uuid !in importedGroupUuids && it.isUseContainer }
            .forEach { SandboxManager.wipeSandboxStorage(it.uuid) }

        val importedAppUuids = importedWebApps.mapTo(mutableSetOf()) { it.uuid }
        websites.filter { it.uuid !in importedAppUuids && it.isUseContainer }
            .forEach { SandboxManager.wipeSandboxStorage(it.uuid) }

        websites = importedWebApps.toMutableList()
        groups = importedGroups.toMutableList()
        _defaultSettings.settings = globalSettings
        saveWebAppData()
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

    fun replaceWebApp(webapp: WebApp) {
        val index = websites.indexOfFirst { it.uuid == webapp.uuid }
        if (index >= 0) {
            websites[index] = webapp
            dao.upsert(webapp.toEntity())
        }
    }

    fun getWebApp(uuid: String): WebApp? = websites.find { it.uuid == uuid }

    fun getWebsites(): List<WebApp> = websites

    val activeWebsites: List<WebApp>
        get() = websites.filter { it.isActiveEntry }.sortedBy { it.order }

    fun activeWebsitesForGroup(groupUuid: String?): List<WebApp> {
        return websites
            .filter { it.isActiveEntry && it.groupUuid == groupUuid }
            .sortedBy { it.order }
    }

    val activeWebsitesCount: Int
        get() = websites.count { it.isActiveEntry }

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

    // Group management

    fun getGroups(): List<WebAppGroup> = groups

    val sortedGroups: List<WebAppGroup>
        get() = groups.sortedBy { it.order }

    fun getGroup(uuid: String): WebAppGroup? = groups.find { it.uuid == uuid }

    fun addGroup(group: WebAppGroup) {
        groups.add(group)
        groupDao.upsert(group.toEntity())
    }

    fun replaceGroup(group: WebAppGroup) {
        val index = groups.indexOfFirst { it.uuid == group.uuid }
        if (index >= 0) {
            groups[index] = group
            groupDao.upsert(group.toEntity())
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
                webapp.isActiveEntry = false
                dao.upsert(webapp.toEntity())
            }
        }
        groups.remove(group)
        groupDao.deleteByUuid(group.uuid)
    }

    fun saveGroupData() {
        groupDao.replaceAll(groups.map { it.toEntity() })
    }

    private fun removeStaleShortcuts(
        oldWebApps: List<WebApp>,
        newWebApps: List<WebApp>,
    ) {
        val staleUuids =
            newWebApps
                .filter { newApp ->
                    oldWebApps.any { it.uuid == newApp.uuid && it.baseUrl != newApp.baseUrl }
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
        @JvmField val instance = DataManager()
    }
}
