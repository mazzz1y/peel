package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.model.db.AppDatabase
import wtf.mazy.peel.model.db.WebAppDao
import wtf.mazy.peel.model.db.WebAppGroupDao
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.model.db.toEntity

class DataRepository {
    private lateinit var webAppDao: WebAppDao
    private lateinit var groupDao: WebAppGroupDao

    val isInitialized: Boolean
        get() = ::webAppDao.isInitialized && ::groupDao.isInitialized

    fun initialize(context: Context) {
        val db = AppDatabase.getInstance(context)
        webAppDao = db.webAppDao()
        groupDao = db.webAppGroupDao()
    }

    fun getGlobalSettings(): WebApp? = webAppDao.getGlobalSettings()?.toDomain()

    fun persistGlobalSettings(defaultSettings: WebApp) {
        webAppDao.upsert(defaultSettings.toEntity())
    }

    fun getWebApp(uuid: String): WebApp? = webAppDao.getByUuid(uuid)?.toDomain()

    fun getGroup(uuid: String): WebAppGroup? = groupDao.getByUuid(uuid)?.toDomain()

    fun getAllWebApps(): List<WebApp> = webAppDao.getAllWebApps().map { it.toDomain() }

    fun getAllGroups(): List<WebAppGroup> = groupDao.getAllGroups().map { it.toDomain() }

    fun upsertWebApp(webApp: WebApp) {
        webAppDao.upsert(webApp.toEntity())
    }

    fun upsertWebApps(webApps: List<WebApp>) {
        webAppDao.upsertAll(webApps.map { it.toEntity() })
    }

    fun deleteWebApp(uuid: String) {
        webAppDao.deleteByUuid(uuid)
    }

    fun replaceAllWebApps(webApps: List<WebApp>) {
        webAppDao.replaceAllWebApps(webApps.map { it.toEntity() })
    }

    fun upsertGroup(group: WebAppGroup) {
        groupDao.upsert(group.toEntity())
    }

    fun upsertGroups(groups: List<WebAppGroup>) {
        groupDao.upsertAll(groups.map { it.toEntity() })
    }

    fun deleteGroup(uuid: String) {
        groupDao.deleteByUuid(uuid)
    }

    fun replaceAllGroups(groups: List<WebAppGroup>) {
        groupDao.replaceAll(groups.map { it.toEntity() })
    }
}
