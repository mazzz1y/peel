package wtf.mazy.peel.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.App
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps a privileged extension in sync with a snapshot derived from [DataManager] state.
 *
 * Each push carries a sequence number; the extension acknowledges the last one it applied,
 * and [awaitReady] gates on that so the state is in place before the first request goes out.
 */
abstract class ExtensionSyncBridge<Snapshot : Any>(
    private val tag: String,
    private val nativeApp: String,
    private val ackType: String,
) {

    private val ext = AtomicReference<WebExtension?>(null)
    private val port = AtomicReference<WebExtension.Port?>(null)
    private val attached = AtomicBoolean(false)
    private val subscriptionStarted = AtomicBoolean(false)

    @Volatile
    private var lastSnapshot: Snapshot? = null

    private val seqCounter = AtomicLong(0)
    private val lastPushedSeq = AtomicLong(0)
    private val lastAckedSeq = AtomicLong(-1)

    private val ready = MutableStateFlow(false)

    protected abstract suspend fun installExtension(context: Context): WebExtension?

    protected abstract fun buildSnapshot(): Snapshot

    protected abstract fun payload(seq: Long, snapshot: Snapshot): JSONObject

    protected open fun hasWork(snapshot: Snapshot): Boolean = true

    protected open fun onAck(message: JSONObject) {}

    suspend fun ensure(context: Context): WebExtension? {
        ext.get()?.let { return it }
        val installed = withContext(Dispatchers.Main) { installExtension(context) }
        if (installed == null) {
            ready.value = true
            return null
        }
        ext.set(installed)
        if (attached.compareAndSet(false, true)) {
            withContext(Dispatchers.Main) { attachMessageDelegate(installed) }
        }
        startSubscription()
        if (!hasWork(buildSnapshot())) ready.value = true
        return installed
    }

    protected suspend fun awaitReady(): Boolean =
        withTimeoutOrNull(READY_TIMEOUT_MS.milliseconds) { ready.first { it } } != null

    private fun attachMessageDelegate(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    val previous = port.getAndSet(newPort)
                    if (previous != null && previous !== newPort) {
                        runCatching { previous.disconnect() }
                    }
                    resetConnection()
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (message !is JSONObject) return
                            if (message.optString("type") == ackType) {
                                onAck(message)
                                handleAck(message.optLong("seq", -1))
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (this@ExtensionSyncBridge.port.compareAndSet(port, null)) {
                                resetConnection()
                            }
                        }
                    })
                    push(force = true)
                }
            },
            nativeApp,
        )
    }

    private fun resetConnection() {
        ready.value = false
        lastSnapshot = null
        lastAckedSeq.set(-1)
    }

    private fun handleAck(seq: Long) {
        if (seq < 0) return
        while (true) {
            val current = lastAckedSeq.get()
            if (seq <= current) return
            if (lastAckedSeq.compareAndSet(current, seq)) break
        }
        if (seq >= lastPushedSeq.get()) ready.value = true
    }

    private fun startSubscription() {
        if (!subscriptionStarted.compareAndSet(false, true)) return
        App.appScope.launch {
            DataManager.state.collect {
                withContext(Dispatchers.Main) { push(force = false) }
            }
        }
    }

    fun push(force: Boolean) {
        val activePort = port.get() ?: return
        val snapshot = buildSnapshot()
        if (!hasWork(snapshot)) {
            lastSnapshot = snapshot
            ready.value = true
            return
        }
        if (!force && snapshot == lastSnapshot) {
            if (lastAckedSeq.get() >= lastPushedSeq.get()) ready.value = true
            return
        }
        val seq = seqCounter.incrementAndGet()
        try {
            ready.value = false
            lastPushedSeq.set(seq)
            activePort.postMessage(payload(seq, snapshot))
            lastSnapshot = snapshot
        } catch (e: Exception) {
            Log.w(tag, "could not push to $nativeApp", e)
            lastPushedSeq.set(lastAckedSeq.get())
            ready.value = true
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5000L
    }
}
