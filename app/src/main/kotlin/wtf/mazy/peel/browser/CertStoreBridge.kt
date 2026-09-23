package wtf.mazy.peel.browser

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager

/**
 * Pushes the user's trusted CA list into the privileged cert-store extension,
 * which writes it to the profile's certificate database. An empty list is still
 * pushed so that certificates removed while the extension was idle get deleted.
 */
object CertStoreBridge : ExtensionSyncBridge<List<String>>(
    tag = TAG,
    nativeApp = "certStore",
    ackType = "sync-ack",
) {

    override suspend fun installExtension(context: Context): WebExtension? =
        GeckoRuntimeProvider.ensureCertStoreExtension(context)

    /**
     * Certificates live in the profile-wide database, so this gates on the
     * global list rather than on any per-app state.
     */
    suspend fun awaitCertsReady() {
        if (buildSnapshot().isEmpty()) return
        if (!awaitReady()) Log.w(TAG, "certificates not ready, loading anyway")
    }

    override fun onAck(message: JSONObject) {
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

    override fun payload(seq: Long, snapshot: List<String>): JSONObject =
        JSONObject()
            .put("cmd", "sync")
            .put("seq", seq)
            .put("certs", JSONArray().apply { snapshot.forEach { put(it) } })

    override fun buildSnapshot(): List<String> =
        DataManager.instance.defaultSettings.settings.trustedCertificates
            ?.filter { it.isNotBlank() }
            .orEmpty()
}

private const val TAG = "CertStoreBridge"
