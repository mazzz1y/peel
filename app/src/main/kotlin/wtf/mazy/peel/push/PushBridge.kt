package wtf.mazy.peel.push

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebPushDelegate
import org.mozilla.geckoview.WebPushSubscription
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.keys.DefaultKeyManager
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.db.PushSubscriptionEntity
import wtf.mazy.peel.util.AppPrefs
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PushBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingRegistrations =
        ConcurrentHashMap<String, CompletableDeferred<PushEndpoint?>>()

    fun attach(runtime: GeckoRuntime, context: Context) {
        val appContext = context.applicationContext
        if (AppPrefs.isPushPermissionResetPending(appContext)) {
            scope.launch {
                resetMatchingPermissions(appContext) { true }
                AppPrefs.setPushPermissionResetPending(appContext, false)
            }
        }
        runtime.webPushController.setDelegate(object : WebPushDelegate {
            override fun onSubscribe(
                scopeUrl: String,
                appServerKey: ByteArray?,
            ): GeckoResult<WebPushSubscription>? =
                geckoResult { subscribe(appContext, scopeUrl, appServerKey) }

            override fun onGetSubscription(scopeUrl: String): GeckoResult<WebPushSubscription>? =
                geckoResult { getSubscription(appContext, scopeUrl) }

            override fun onUnsubscribe(scopeUrl: String): GeckoResult<Void>? {
                val result = GeckoResult<Void>()
                scope.launch {
                    unsubscribe(appContext, scopeUrl)
                    result.complete(null)
                }
                return result
            }
        })
    }

    private fun geckoResult(
        block: suspend () -> WebPushSubscription?,
    ): GeckoResult<WebPushSubscription> {
        val result = GeckoResult<WebPushSubscription>()
        scope.launch {
            result.complete(runCatching { block() }.getOrNull())
        }
        return result
    }

    private suspend fun subscribe(
        context: Context,
        scopeUrl: String,
        appServerKey: ByteArray?,
    ): WebPushSubscription? {
        DataManager.instance.awaitReady()
        if (!AppPrefs.isPushEnabled(context)) return null
        if (isPrivateScope(scopeUrl) || isEphemeralScope(scopeUrl)) return null
        getSubscription(context, scopeUrl)?.let { return it }
        if (!ensureDistributor(context)) return null

        val instance = UUID.randomUUID().toString()
        val vapid = appServerKey?.let { encodeKey(it) }
        val deferred = CompletableDeferred<PushEndpoint?>()
        pendingRegistrations[instance] = deferred
        val endpoint = try {
            UnifiedPush.register(
                context,
                instance,
                messageForDistributor = distributorLabel(scopeUrl),
                vapid = vapid,
            )
            withTimeoutOrNull(REGISTER_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingRegistrations.remove(instance)
        }
        if (endpoint?.pubKeySet == null) {
            UnifiedPush.unregister(context, instance)
            return null
        }

        val entity = PushSubscriptionEntity(
            instance = instance,
            contextId = scopeContextId(scopeUrl),
            scope = scopeUrl,
            endpoint = endpoint.url,
            appServerKey = vapid,
        )
        DataManager.instance.upsertPushSubscription(entity)
        return entity.toWebPushSubscription(context)
    }

    private suspend fun getSubscription(context: Context, scopeUrl: String): WebPushSubscription? {
        DataManager.instance.awaitReady()
        val entity = DataManager.instance.getPushSubscriptionByScope(scopeUrl) ?: return null
        val subscription = entity.toWebPushSubscription(context)
        if (subscription == null) removeRegistration(context, entity)
        return subscription
    }

    private suspend fun unsubscribe(context: Context, scopeUrl: String) {
        DataManager.instance.awaitReady()
        val entity = DataManager.instance.getPushSubscriptionByScope(scopeUrl) ?: return
        removeRegistration(context, entity)
    }

    suspend fun resetSite(
        context: Context,
        permission: ContentPermission?,
        subscription: PushSubscriptionEntity?,
    ) {
        subscription?.let { removeRegistration(context, it) }
        val permissions = when {
            permission != null -> listOf(permission)
            subscription != null -> notificationPermissions(context) {
                it.getPermissions(scopeOrigin(subscription.scope), subscription.contextId, false)
            }

            else -> return
        }
        resetPermissions(context, permissions)
    }

    fun onContextCleared(context: Context, contextId: String) {
        val appContext = context.applicationContext
        scope.launch {
            clearContext(appContext, contextId) { it.contextId == contextId }
            WebNotificationBridge.removeChannel(appContext, contextId)
        }
    }

    fun onAllContextsCleared(context: Context) {
        val appContext = context.applicationContext
        scope.launch { reset(appContext) }
    }

    suspend fun getNotificationPermissions(context: Context): List<ContentPermission> =
        notificationPermissions(context) { it.getAllPermissions() }
            .filter { !it.privateMode && it.value != ContentPermission.VALUE_PROMPT }

    private suspend fun clearContext(
        context: Context,
        contextId: String?,
        requireRuntime: Boolean = true,
        matches: (ContentPermission) -> Boolean,
    ) {
        DataManager.instance.awaitReady()
        subscriptionsForContext(contextId).forEach { removeRegistration(context, it) }
        if (!requireRuntime && GeckoRuntimeProvider.runtimeOrNull() == null) {
            AppPrefs.setPushPermissionResetPending(context, true)
            return
        }
        resetMatchingPermissions(context, matches)
    }

    private suspend fun subscriptionsForContext(contextId: String?): List<PushSubscriptionEntity> =
        withContext(Dispatchers.IO) {
            contextId
                ?.let { DataManager.instance.getPushSubscriptionsForContext(it) }
                ?: DataManager.instance.getPushSubscriptions()
        }

    private suspend fun removeRegistration(context: Context, entity: PushSubscriptionEntity) {
        withContext(Dispatchers.IO) { UnifiedPush.unregister(context, entity.instance) }
        DataManager.instance.removePushSubscription(entity.instance)
    }

    private suspend fun resetMatchingPermissions(
        context: Context,
        matches: (ContentPermission) -> Boolean,
    ) {
        val permissions = notificationPermissions(context) { it.getAllPermissions() }
            .filter(matches)
        if (permissions.isNotEmpty()) resetPermissions(context, permissions)
    }

    private suspend fun notificationPermissions(
        context: Context,
        query: (StorageController) -> GeckoResult<List<ContentPermission>>,
    ): List<ContentPermission> = withContext(Dispatchers.Main) {
        val storage = GeckoRuntimeProvider.getRuntime(context).storageController
        runCatching { query(storage).awaitList() }
            .getOrNull()
            .orEmpty()
            .filter {
                it.permission == GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION
            }
    }

    private suspend fun resetPermissions(
        context: Context,
        permissions: List<ContentPermission>,
    ) = withContext(Dispatchers.Main) {
        val storage = GeckoRuntimeProvider.getRuntime(context).storageController
        permissions.forEach { storage.setPermission(it, ContentPermission.VALUE_PROMPT) }
    }

    suspend fun reconcile(context: Context) {
        DataManager.instance.awaitReady()
        val subscriptions = withContext(Dispatchers.IO) {
            DataManager.instance.getPushSubscriptions()
        }
        if (subscriptions.isEmpty()) return

        val linkedInstalled = withContext(Dispatchers.IO) {
            val linked = UnifiedPush.getAckDistributor(context)
            linked != null && linked in UnifiedPush.getDistributors(context)
        }
        if (AppPrefs.isPushEnabled(context) && linkedInstalled) {
            subscriptions.forEach { reRegister(context, it) }
        } else {
            AppPrefs.setPushEnabled(context, false)
            reset(context, requireRuntime = false)
        }
    }

    suspend fun reset(context: Context, requireRuntime: Boolean = true) {
        withContext(Dispatchers.IO) { UnifiedPush.removeDistributor(context) }
        clearContext(context, null, requireRuntime) { true }
    }

    fun matchesScope(permission: ContentPermission, scopeUrl: String): Boolean =
        scopeOrigin(permission.uri) == scopeOrigin(scopeUrl) &&
                permission.contextId.orEmpty() == scopeContextId(scopeUrl).orEmpty()

    private fun reRegister(context: Context, entity: PushSubscriptionEntity) {
        UnifiedPush.register(
            context,
            entity.instance,
            messageForDistributor = distributorLabel(entity.scope),
            vapid = entity.appServerKey,
        )
    }

    fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        pendingRegistrations[instance]?.let {
            it.complete(endpoint)
            return
        }
        scope.launch {
            DataManager.instance.awaitReady()
            val existing = DataManager.instance.getPushSubscription(instance)
            if (existing == null) {
                UnifiedPush.unregister(context, instance)
                return@launch
            }
            if (endpoint.pubKeySet == null) return@launch
            if (existing.endpoint == endpoint.url) return@launch
            DataManager.instance.upsertPushSubscription(existing.copy(endpoint = endpoint.url))
            GeckoRuntimeProvider.getRuntime(context)
                .webPushController
                .onSubscriptionChanged(existing.scope)
        }
    }

    fun onMessage(context: Context, content: ByteArray, instance: String) {
        scope.launch {
            DataManager.instance.awaitReady()
            val entity = DataManager.instance.getPushSubscription(instance) ?: return@launch
            GeckoRuntimeProvider.getRuntime(context)
                .webPushController
                .onPushEvent(entity.scope, content)
        }
    }

    fun onUnregistered(context: Context, instance: String) {
        pendingRegistrations[instance]?.complete(null)
        scope.launch {
            DataManager.instance.awaitReady()
            val entity = DataManager.instance.getPushSubscription(instance)
            DataManager.instance.removePushSubscription(instance)
            entity?.let {
                GeckoRuntimeProvider.getRuntime(context)
                    .webPushController
                    .onSubscriptionChanged(it.scope)
            }
        }
    }

    fun onRegistrationFailed(instance: String) {
        pendingRegistrations[instance]?.complete(null)
    }

    private suspend fun ensureDistributor(context: Context): Boolean {
        if (UnifiedPush.getSavedDistributor(context) != null) return true
        AppPrefs.setPushEnabled(context, false)
        if (DataManager.instance.getPushSubscriptions().isNotEmpty()) reset(context)
        return false
    }

    fun scopeUrlWithoutAttrs(scopeUrl: String): String = scopeUrl.substringBefore(ATTRS_SEPARATOR)

    fun scopeOrigin(scopeUrl: String): String {
        val uri = scopeUrlWithoutAttrs(scopeUrl).toUri()
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        return "${uri.scheme}://${uri.host}$port"
    }

    fun scopeHost(scopeUrl: String): String? =
        runCatching { scopeUrlWithoutAttrs(scopeUrl).toUri().host }.getOrNull()

    private fun distributorLabel(scopeUrl: String): String? {
        val owner = scopeContextId(scopeUrl)?.let(DataManager.instance::getSandboxOwner)
        return (owner as? IconOwner)?.title ?: scopeHost(scopeUrl)
    }

    fun scopeContextId(scopeUrl: String): String? {
        val suffix = scopeUrl.substringAfter(ATTRS_SEPARATOR, "")
        if (suffix.isEmpty()) return null
        val encoded = suffix.split('&')
            .firstOrNull { it.startsWith(CONTEXT_ID_ATTR) }
            ?.substringAfter('=')
            ?: return null
        return decodeGeckoContextId(encoded)
    }

    private fun isPrivateScope(scopeUrl: String): Boolean {
        val suffix = scopeUrl.substringAfter(ATTRS_SEPARATOR, "")
        return suffix.split('&').any { it.startsWith("privateBrowsingId=") && !it.endsWith("=0") }
    }

    private fun isEphemeralScope(scopeUrl: String): Boolean {
        val contextId = scopeContextId(scopeUrl) ?: return false
        return DataManager.instance.getSandboxOwner(contextId)?.resolveEphemeral() == true
    }

    private fun decodeGeckoContextId(encoded: String): String? {
        if (!encoded.startsWith(GECKO_CONTEXT_PREFIX)) return null
        if (encoded == GECKO_CONTEXT_EMPTY) return null
        return runCatching {
            String(
                BigInteger(encoded.removePrefix(GECKO_CONTEXT_PREFIX), 16).toByteArray(),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    private fun PushSubscriptionEntity.toWebPushSubscription(context: Context): WebPushSubscription? {
        val keys = DefaultKeyManager(context).getPublicKeySet(instance) ?: return null
        return WebPushSubscription(
            scope,
            endpoint,
            appServerKey?.let { decodeKey(it) },
            decodeKey(keys.pubKey),
            decodeKey(keys.auth),
        )
    }

    private fun encodeKey(raw: ByteArray): String =
        Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decodeKey(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private suspend fun <T> GeckoResult<T>.awaitList(): T? =
        suspendCancellableCoroutine { cont ->
            then(
                { value -> cont.resume(value); GeckoResult() },
                { throwable -> cont.resumeWithException(throwable); GeckoResult<Void>() },
            )
        }

    private const val ATTRS_SEPARATOR = '^'
    private const val CONTEXT_ID_ATTR = "geckoViewUserContextId="
    private const val GECKO_CONTEXT_PREFIX = "gvctx"
    private const val GECKO_CONTEXT_EMPTY = "gvctxempty"
    private const val REGISTER_TIMEOUT_MS = 10_000L
}
