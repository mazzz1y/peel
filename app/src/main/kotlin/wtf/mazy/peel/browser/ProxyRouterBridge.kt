package wtf.mazy.peel.browser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ProxyRouterBridge {

    const val NATIVE_APP = "proxyRouter"
    private const val CONTAINER_PREFIX = "firefox-container-"

    private val ext = AtomicReference<WebExtension?>(null)
    private val port = AtomicReference<WebExtension.Port?>(null)
    private val attached = AtomicBoolean(false)
    private val subscriptionStarted = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastSnapshot: Map<String, Map<String, Any?>>? = null

    private val seqCounter = AtomicLong(0)
    private val lastPushedSeq = AtomicLong(0)
    private val lastAckedSeq = AtomicLong(-1)

    private val routesReady = MutableStateFlow(false)

    suspend fun ensure(context: Context): WebExtension? {
        ext.get()?.let { return it }
        val installed = withContext(Dispatchers.Main) {
            GeckoRuntimeProvider.ensureProxyRouterExtension(context)
        }
        if (installed == null) {
            routesReady.value = true
            return null
        }
        ext.set(installed)
        if (attached.compareAndSet(false, true)) {
            withContext(Dispatchers.Main) { attachMessageDelegate(installed) }
        }
        startSubscription()
        if (buildSnapshot().isEmpty()) {
            routesReady.value = true
        }
        return installed
    }

    suspend fun awaitRoutesReady() {
        routesReady.first { it }
    }

    private fun attachMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    val previous = port.getAndSet(newPort)
                    if (previous != null && previous !== newPort) {
                        runCatching { previous.disconnect() }
                    }
                    routesReady.value = false
                    lastSnapshot = null
                    lastAckedSeq.set(-1)
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            port: WebExtension.Port,
                        ) {
                            if (message !is JSONObject) return
                            if (message.optString("type") == "routes-ack") {
                                handleAck(message.optLong("seq", -1))
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (this@ProxyRouterBridge.port.compareAndSet(port, null)) {
                                routesReady.value = false
                                lastSnapshot = null
                                lastAckedSeq.set(-1)
                            }
                        }
                    })
                    pushRoutes(force = true)
                }
            },
            NATIVE_APP,
        )
    }

    private fun handleAck(seq: Long) {
        if (seq < 0) return
        while (true) {
            val current = lastAckedSeq.get()
            if (seq <= current) return
            if (lastAckedSeq.compareAndSet(current, seq)) break
        }
        if (seq >= lastPushedSeq.get()) {
            routesReady.value = true
        }
    }

    private fun startSubscription() {
        if (!subscriptionStarted.compareAndSet(false, true)) return
        scope.launch {
            DataManager.instance.state.collect { _ ->
                withContext(Dispatchers.Main) { pushRoutes(force = false) }
            }
        }
    }

    fun pushRoutes(force: Boolean) {
        val activePort = port.get() ?: return
        val snapshot = buildSnapshot()
        if (snapshot.isEmpty()) {
            lastSnapshot = snapshot
            routesReady.value = true
            return
        }
        if (!force && snapshot == lastSnapshot) {
            if (lastAckedSeq.get() >= lastPushedSeq.get()) {
                routesReady.value = true
            }
            return
        }
        val seq = seqCounter.incrementAndGet()
        val payload = JSONObject()
        payload.put("cmd", "set-routes")
        payload.put("seq", seq)
        val routesJson = JSONObject()
        for ((storeId, cfg) in snapshot) {
            routesJson.put(storeId, configToJson(cfg))
        }
        payload.put("routes", routesJson)
        try {
            routesReady.value = false
            lastPushedSeq.set(seq)
            activePort.postMessage(payload)
            lastSnapshot = snapshot
        } catch (_: Exception) {
        }
    }

    private fun buildSnapshot(): Map<String, Map<String, Any?>> {
        val dm = DataManager.instance
        val proxies = dm.getProxies().associateBy { it.uuid }
        val out = LinkedHashMap<String, Map<String, Any?>>()

        val direct = mapOf<String, Any?>("type" to "direct")

        for (group in dm.getGroups()) {
            if (!group.isUseContainer) continue
            val storeId = CONTAINER_PREFIX + group.uuid
            val proxy = group.proxyUuid?.let { proxies[it] }
            out[storeId] = proxy?.let(::proxyToMap) ?: direct
        }
        for (app in dm.getWebsites()) {
            if (!app.isUseContainer) continue
            val contextId = app.resolveContextId() ?: continue
            val storeId = CONTAINER_PREFIX + contextId
            if (out.containsKey(storeId)) continue
            val pUuid = app.proxyUuid
                ?: app.groupUuid
                    ?.let { gid -> dm.getGroup(gid)?.takeIf { it.isUseContainer }?.proxyUuid }
            val proxy = pUuid?.let { proxies[it] }
            out[storeId] = proxy?.let(::proxyToMap) ?: direct
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
