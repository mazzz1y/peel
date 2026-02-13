package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.model.db.AppDatabase
import wtf.mazy.peel.model.db.LegacySharedPrefsMigration
import wtf.mazy.peel.model.db.WebAppDao
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.model.db.toEntity
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

class DataManager private constructor() {
    private var websites: MutableList<WebApp> = mutableListOf()
    private lateinit var dao: WebAppDao

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
        @Suppress("DEPRECATION")
        LegacySharedPrefsMigration.migrate(context, dao, db.sandboxSlotDao())
        if (dao.getGlobalSettings() == null) saveDefaultSettings()
        loadAppData()
    }

    fun loadAppData() {
        val newWebsites = dao.getAllWebApps().mapTo(mutableListOf()) { it.toDomain() }
        removeStaleShortcuts(websites, newWebsites)
        websites = newWebsites

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

    fun importData(importedWebApps: List<WebApp>, globalSettings: WebAppSettings) {
        websites = importedWebApps.toMutableList()
        _defaultSettings.settings = globalSettings
        saveWebAppData()
        saveDefaultSettings()
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

    val activeWebsitesCount: Int
        get() = websites.count { it.isActiveEntry }

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

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
