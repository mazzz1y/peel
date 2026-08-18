package wtf.mazy.peel.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pushes the user's trusted CA list into the privileged cert-store extension,
 * which writes it to the profile's certificate database.
 *
 * Trust has to be in place before the first request goes out, otherwise the
 * page fails with a certificate error and the user has to reload; callers wait
 * on [awaitCertsReady] before loading. Readiness is tracked with a
 * sequence/ack pair, mirroring [ProxyRouterBridge].
 */
object CertStoreBridge {

    const val NATIVE_APP = "certStore"

    private val ext = AtomicReference<WebExtension?>(null)
    private val port = AtomicReference<WebExtension.Port?>(null)
    private val attached = AtomicBoolean(false)
    private val subscriptionStarted = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastSnapshot: List<String>? = null

    private val seqCounter = AtomicLong(0)
    private val lastPushedSeq = AtomicLong(0)
    private val lastAckedSeq = AtomicLong(-1)

    private val certsReady = MutableStateFlow(false)

    suspend fun ensure(context: Context): WebExtension? {
        ext.get()?.let { return it }
        // The extension is installed even with an empty list, so that
        // certificates removed while it was idle still get deleted.
        val installed = withContext(Dispatchers.Main) {
            GeckoRuntimeProvider.ensureCertStoreExtension(context)
        }
        if (installed == null) {
            certsReady.value = true
            return null
        }
        ext.set(installed)
        if (attached.compareAndSet(false, true)) {
            withContext(Dispatchers.Main) { attachMessageDelegate(installed) }
        }
        startSubscription()
        return installed
    }

    /**
     * Certificates live in the profile-wide database, so this gates on the
     * global list rather than on any per-app state.
     */
    suspend fun awaitCertsReady() {
        if (buildSnapshot().isEmpty()) return
        if (withTimeoutOrNull(READY_TIMEOUT_MS.milliseconds) { certsReady.first { it } } == null) {
            Log.w(TAG, "certificates not ready after ${READY_TIMEOUT_MS}ms, loading anyway")
        }
    }

    private fun attachMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    val previous = port.getAndSet(newPort)
                    if (previous != null && previous !== newPort) {
                        runCatching { previous.disconnect() }
                    }
                    certsReady.value = false
                    lastSnapshot = null
                    lastAckedSeq.set(-1)
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (message !is JSONObject) return
                            if (message.optString("type") == "sync-ack") {
                                logSyncResult(message)
                                handleAck(message.optLong("seq", -1))
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (this@CertStoreBridge.port.compareAndSet(port, null)) {
                                certsReady.value = false
                                lastSnapshot = null
                                lastAckedSeq.set(-1)
                            }
                        }
                    })
                    pushCerts(force = true)
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
            certsReady.value = true
        }
    }

    private fun logSyncResult(message: JSONObject) {
        val failure = message.optString("failure").takeIf { it.isNotEmpty() && it != "null" }
        if (failure != null) {
            Log.w(TAG, "cert sync failed: $failure")
        }
        val errors = message.optJSONArray("errors") ?: return
        for (i in 0 until errors.length()) {
            val error = errors.optJSONObject(i) ?: continue
            Log.w(TAG, "cert rejected: ${error.optString("reason")} ${error.optString("message")}")
        }
    }

    private fun startSubscription() {
        if (!subscriptionStarted.compareAndSet(false, true)) return
        scope.launch {
            DataManager.instance.state.collect {
                withContext(Dispatchers.Main) { pushCerts(force = false) }
            }
        }
    }

    fun pushCerts(force: Boolean) {
        val activePort = port.get() ?: return
        val snapshot = buildSnapshot()
        if (!force && snapshot == lastSnapshot) {
            if (lastAckedSeq.get() >= lastPushedSeq.get()) {
                certsReady.value = true
            }
            return
        }
        val seq = seqCounter.incrementAndGet()
        val payload = JSONObject()
        payload.put("cmd", "sync")
        payload.put("seq", seq)
        payload.put("certs", JSONArray().apply { snapshot.forEach { put(it) } })
        try {
            certsReady.value = false
            lastPushedSeq.set(seq)
            activePort.postMessage(payload)
            lastSnapshot = snapshot
        } catch (e: Exception) {
            Log.w(TAG, "could not push certificates", e)
        }
    }

    private fun buildSnapshot(): List<String> =
        DataManager.instance.defaultSettings.settings.trustedCertificates
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private const val TAG = "CertStoreBridge"
    private const val READY_TIMEOUT_MS = 5000L
}
