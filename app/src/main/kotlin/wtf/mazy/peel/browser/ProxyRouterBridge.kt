package wtf.mazy.peel.browser

import android.content.Context
import androidx.annotation.CheckResult
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSandbox
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.effectiveSandbox

object ProxyRouterBridge : ExtensionSyncBridge<Map<String, Map<String, Any?>>>(
    tag = "ProxyRouterBridge",
    nativeApp = "proxyRouter",
    ackType = "routes-ack",
) {

    private const val CONTAINER_PREFIX = "firefox-container-"
    private val DIRECT = mapOf<String, Any?>("type" to "direct")

    override suspend fun installExtension(context: Context): WebExtension? =
        GeckoRuntimeProvider.ensureProxyRouterExtension(context)

    override fun hasWork(snapshot: Map<String, Map<String, Any?>>): Boolean = snapshot.isNotEmpty()

    @CheckResult
    suspend fun awaitRoutesReady(contextId: String?): Boolean {
        if (contextId == null || !hasProxiedRoute(contextId)) return true
        return awaitReady()
    }

    private fun hasProxiedRoute(contextId: String): Boolean {
        val cfg = buildSnapshot()[CONTAINER_PREFIX + contextId] ?: return false
        return cfg !== DIRECT
    }

    override fun payload(seq: Long, snapshot: Map<String, Map<String, Any?>>): JSONObject {
        val routesJson = JSONObject()
        for ((storeId, cfg) in snapshot) {
            routesJson.put(storeId, configToJson(cfg))
        }
        return JSONObject()
            .put("cmd", "set-routes")
            .put("seq", seq)
            .put("routes", routesJson)
    }

    override fun buildSnapshot(): Map<String, Map<String, Any?>> {
        val state = DataManager.state.value
        val proxies = state.proxies.associateBy { it.uuid }
        val groups = state.groups.associateBy { it.uuid }
        val sandboxes = state.groups.map(::effectiveSandbox) +
                (state.webApps + DataManager.transientWebAppList).map { app ->
                    effectiveSandbox(app, app.groupUuid?.let(groups::get))
                }

        val out = LinkedHashMap<String, Map<String, Any?>>()
        for (sandbox in sandboxes) {
            if (sandbox !is EffectiveSandbox.Own) continue
            val storeId = CONTAINER_PREFIX + sandbox.contextId
            if (storeId in out) continue
            out[storeId] = when (val route = sandbox.route) {
                ProxyRoute.Direct -> DIRECT
                is ProxyRoute.Via -> proxies[route.proxyUuid]?.let(::proxyToMap) ?: continue
            }
        }
        return out
    }

    private fun proxyToMap(proxy: Proxy): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["type"] = typeString(proxy.type)
        m["host"] = proxy.host
        m["port"] = proxy.port
        proxy.username?.takeIf { it.isNotEmpty() }?.let { m["username"] = it }
        proxy.password?.takeIf { it.isNotEmpty() }?.let { m["password"] = it }
        m["remoteDns"] = proxy.remoteDns
        if (proxy.bypassList.isNotEmpty()) m["bypass"] = proxy.bypassList.toList()
        return m
    }

    private fun configToJson(cfg: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in cfg) {
            when (v) {
                is List<*> -> {
                    val arr = JSONArray()
                    for (item in v) if (item != null) arr.put(item)
                    obj.put(k, arr)
                }

                null -> {}
                else -> obj.put(k, v)
            }
        }
        return obj
    }

    private fun typeString(type: Int): String = when (type) {
        Proxy.TYPE_HTTP -> "http"
        Proxy.TYPE_HTTPS -> "https"
        Proxy.TYPE_SOCKS4 -> "socks4"
        Proxy.TYPE_SOCKS5 -> "socks5"
        else -> "http"
    }
}
