package wtf.mazy.peel.model

import wtf.mazy.peel.model.db.AppDatabase
import wtf.mazy.peel.model.db.PushSubscriptionEntity
import wtf.mazy.peel.model.db.toDomain
import wtf.mazy.peel.model.db.toEntity

class DataRepository(db: AppDatabase) {
    private val webAppDao = db.webAppDao()
    private val groupDao = db.webAppGroupDao()
    private val proxyDao = db.proxyDao()
    private val pushSubscriptionDao = db.pushSubscriptionDao()

    fun loadGlobalSettings(): WebApp? = webAppDao.getGlobalSettings()?.toDomain()

    fun persistGlobalSettings(globalSettings: WebApp) {
        webAppDao.upsert(globalSettings.toEntity())
    }

    fun loadWebApp(uuid: String): WebApp? = webAppDao.getByUuid(uuid)?.toDomain()

    fun loadGroup(uuid: String): WebAppGroup? = groupDao.getByUuid(uuid)?.toDomain()

    fun loadAllWebApps(): List<WebApp> = webAppDao.getAllWebApps().map { it.toDomain() }

    fun loadAllGroups(): List<WebAppGroup> = groupDao.getAllGroups().map { it.toDomain() }

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

    fun loadAllProxies(): List<Proxy> = proxyDao.getAll().map { it.toDomain() }

    fun upsertProxy(proxy: Proxy) {
        proxyDao.upsert(proxy.toEntity())
    }

    fun upsertProxies(proxies: List<Proxy>) {
        proxyDao.upsertAll(proxies.map { it.toEntity() })
    }

    fun deleteProxy(uuid: String) {
        proxyDao.deleteByUuid(uuid)
    }

    fun replaceAllProxies(proxies: List<Proxy>) {
        proxyDao.replaceAll(proxies.map { it.toEntity() })
    }

    fun loadAllPushSubscriptions(): List<PushSubscriptionEntity> = pushSubscriptionDao.getAll()

    fun loadPushSubscription(instance: String): PushSubscriptionEntity? =
        pushSubscriptionDao.getByInstance(instance)

    fun loadPushSubscriptionByScope(scope: String): PushSubscriptionEntity? =
        pushSubscriptionDao.getByScope(scope)

    fun loadPushSubscriptionsForContext(contextId: String): List<PushSubscriptionEntity> =
        pushSubscriptionDao.getByContextId(contextId)

    fun upsertPushSubscription(entity: PushSubscriptionEntity) {
        pushSubscriptionDao.upsert(entity)
    }

    fun deletePushSubscription(instance: String) {
        pushSubscriptionDao.deleteByInstance(instance)
    }
}
