package wtf.mazy.peel.activities

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.shortcut.HeadlessWebViewFetcher

open class SandboxFetchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sandboxId = intent?.getStringExtra(EXTRA_SANDBOX_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val settingsJson = intent?.getStringExtra(EXTRA_SETTINGS)
        val receiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RECEIVER)
        }

        if (sandboxId == null || url == null || receiver == null) {
            sendResult(receiver, null, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!SandboxManager.initDataDirectorySuffix(sandboxId)) {
            sendResult(receiver, null, null)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val settings = settingsJson?.let {
            try { Json.decodeFromString<WebAppSettings>(it) } catch (_: Exception) { null }
        } ?: WebAppSettings.createWithDefaults()

        HeadlessWebViewFetcher(this, url, settings) { title, icon ->
            sendResult(receiver, title, icon)
            stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    private fun sendResult(receiver: ResultReceiver?, title: String?, icon: Bitmap?) {
        val bundle = Bundle()
        if (title != null) bundle.putString(RESULT_TITLE, title)
        if (icon != null) {
            val stream = java.io.ByteArrayOutputStream()
            icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bundle.putByteArray(RESULT_ICON, stream.toByteArray())
        }
        receiver?.send(0, bundle)
    }

    companion object {
        const val EXTRA_SANDBOX_ID = "sandbox_id"
        const val EXTRA_URL = "fetch_url"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_RECEIVER = "receiver"
        const val RESULT_TITLE = "result_title"
        const val RESULT_ICON = "result_icon"

        fun createIntent(
            context: Context,
            slotId: Int,
            sandboxId: String,
            url: String,
            settings: WebAppSettings,
            receiver: ResultReceiver,
        ): Intent {
            val className = "wtf.mazy.peel.activities.SandboxFetchService$slotId"
            val serviceClass = try {
                Class.forName(className)
            } catch (_: ClassNotFoundException) {
                SandboxFetchService::class.java
            }
            return Intent(context, serviceClass).apply {
                putExtra(EXTRA_SANDBOX_ID, sandboxId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_SETTINGS, Json.encodeToString(settings))
                putExtra(EXTRA_RECEIVER, receiver)
            }
        }
    }
}

class SandboxFetchService0 : SandboxFetchService()
class SandboxFetchService1 : SandboxFetchService()
class SandboxFetchService2 : SandboxFetchService()
class SandboxFetchService3 : SandboxFetchService()
