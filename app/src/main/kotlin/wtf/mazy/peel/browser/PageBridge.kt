package wtf.mazy.peel.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider

/**
 * Connects to the page-bridge built-in extension on a [GeckoSession] and
 * exposes authenticated fetch via the page's content script.
 *
 * Isolation: each PageBridge instance binds to exactly one GeckoSession.
 * Cookies and HTTP cache used by fetch are partitioned by the session's
 * contextId, so a bridge in webapp A cannot fetch with webapp B's
 * credentials. The shared WebExtension handle is read-only; routing of
 * port messages is per-session.
 *
 * The content script connects a fresh native port on every page load, so this
 * tracks the most recently connected port and routes responses by `requestId`.
 */
class PageBridge(
    private val ext: WebExtension,
    private val session: GeckoSession,
) {
    class BinaryResult(
        val bytes: ByteArray,
        val contentType: String?,
        val contentDisposition: String?,
    )

    private var port: WebExtension.Port? = null
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject>>()
    private var requestCounter = 0

    fun attach() {
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    val previous = port
                    if (previous != null && previous !== newPort) {
                        runCatching { previous.disconnect() }
                    }
                    port = newPort
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (message !is JSONObject) return
                            val requestId = message.optInt("requestId", -1)
                            if (requestId < 1) return
                            pending.remove(requestId)?.complete(message)
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (this@PageBridge.port === port) {
                                this@PageBridge.port = null
                            }
                            pending.values.forEach { it.complete(disconnectedResponse()) }
                            pending.clear()
                        }
                    })
                }
            },
            GeckoRuntimeProvider.PAGE_BRIDGE_APP,
        )
    }

    fun detach(closingSession: Boolean = false) {
        if (!closingSession) port?.let { runCatching { it.disconnect() } }
        port = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching {
            session.webExtensionController.setMessageDelegate(
                ext,
                null,
                GeckoRuntimeProvider.PAGE_BRIDGE_APP,
            )
        }
    }

    suspend fun fetchBinary(url: String): BinaryResult? {
        val activePort = port ?: return null
        val requestId = ++requestCounter
        val deferred = CompletableDeferred<JSONObject>()
        pending[requestId] = deferred
        val command = JSONObject()
            .put("cmd", "fetch-binary")
            .put("url", url)
            .put("requestId", requestId)
        try {
            activePort.postMessage(command)
        } catch (_: Exception) {
            pending.remove(requestId)
            return null
        }
        val response = withTimeoutOrNull(BINARY_TIMEOUT_MS) { deferred.await() }
            ?: run {
                pending.remove(requestId)
                return null
            }
        if (!response.optBoolean("ok", false)) return null
        val base64 = response.optString("base64", "").takeIf { it.isNotEmpty() } ?: return null
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val contentType = response.optString("contentType", "").takeIf { it.isNotBlank() }
        val disposition = response.optString("disposition", "").takeIf { it.isNotBlank() }
        return BinaryResult(bytes, contentType, disposition)
    }

    private fun disconnectedResponse(): JSONObject =
        JSONObject().put("mode", "binary").put("ok", false).put("error", "disconnected")

    private companion object {
        const val BINARY_TIMEOUT_MS = 35_000L
    }
}
