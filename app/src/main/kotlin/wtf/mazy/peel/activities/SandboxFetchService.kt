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
import wtf.mazy.peel.shortcut.FetchCandidate
import wtf.mazy.peel.shortcut.FetchResult
import wtf.mazy.peel.shortcut.HeadlessWebViewFetcher

open class SandboxFetchService : Service() {

    private var activeFetcher: HeadlessWebViewFetcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeFetcher?.cancel()
        activeFetcher = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sandboxId = intent?.getStringExtra(EXTRA_SANDBOX_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val settingsJson = intent?.getStringExtra(EXTRA_SETTINGS)
        val receiver =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RECEIVER)
            }

        if (sandboxId == null || url == null || receiver == null) {
            receiver?.send(0, Bundle())
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!SandboxManager.initDataDirectorySuffix(sandboxId)) {
            receiver?.send(0, Bundle())
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val settings =
            settingsJson?.let {
                try {
                    Json.decodeFromString<WebAppSettings>(it)
                } catch (_: Exception) {
                    null
                }
            } ?: WebAppSettings.createWithDefaults()

        val fetcher = HeadlessWebViewFetcher(
            this,
            url,
            settings,
            onProgress = { text ->
                receiver.send(RESULT_PROGRESS, Bundle().apply { putString(KEY_PROGRESS, text) })
            },
            onResult = { result ->
                val bundle = Bundle()
                val list = ArrayList<Bundle>(result.candidates.size)
                for (c in result.candidates) {
                    val entry = Bundle()
                    if (c.title != null) entry.putString(KEY_TITLE, c.title)
                    entry.putString(KEY_SOURCE, c.source)
                    if (c.startUrl != null) entry.putString(KEY_START_URL, c.startUrl)
                    if (c.icon != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        c.icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        entry.putByteArray(KEY_ICON, stream.toByteArray())
                    }
                    list.add(entry)
                }
                bundle.putParcelableArrayList(RESULT_CANDIDATES, list)
                if (result.redirectedUrl != null) bundle.putString(
                    KEY_REDIRECTED_URL,
                    result.redirectedUrl
                )
                receiver.send(RESULT_DONE, bundle)
                stopSelf(startId)
            },
        )
        activeFetcher = fetcher
        fetcher.start()

        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_SANDBOX_ID = "sandbox_id"
        const val EXTRA_URL = "fetch_url"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_RECEIVER = "receiver"
        const val RESULT_DONE = 0
        const val RESULT_PROGRESS = 1
        const val RESULT_CANDIDATES = "result_candidates"
        const val KEY_TITLE = "title"
        const val KEY_ICON = "icon"
        const val KEY_SOURCE = "source"
        const val KEY_START_URL = "start_url"
        const val KEY_REDIRECTED_URL = "redirected_url"
        const val KEY_PROGRESS = "progress"

        fun parseResult(resultData: Bundle?): FetchResult {
            val redirectedUrl = resultData?.getString(KEY_REDIRECTED_URL)
            val list =
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    resultData?.getParcelableArrayList(RESULT_CANDIDATES, Bundle::class.java)
                } else {
                    @Suppress("DEPRECATION") resultData?.getParcelableArrayList(RESULT_CANDIDATES)
                } ?: return FetchResult(emptyList(), redirectedUrl)
            val candidates = list.map { entry ->
                val title = entry.getString(KEY_TITLE)
                val source = entry.getString(KEY_SOURCE) ?: ""
                val startUrl = entry.getString(KEY_START_URL)
                val iconBytes = entry.getByteArray(KEY_ICON)
                val icon =
                    iconBytes?.let {
                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                FetchCandidate(title, icon, source, startUrl)
            }
            return FetchResult(candidates, redirectedUrl)
        }

        fun createIntent(
            context: Context,
            slotId: Int,
            sandboxId: String,
            url: String,
            settings: WebAppSettings,
            receiver: ResultReceiver,
        ): Intent {
            val serviceClass =
                Class.forName("wtf.mazy.peel.activities.SandboxFetchService$slotId")
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
