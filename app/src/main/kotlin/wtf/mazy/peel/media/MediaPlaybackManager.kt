package wtf.mazy.peel.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.Locale

class MediaPlaybackManager(
    private val context: Context,
) : MediaJsBridge.Listener {

    private var webView: WebView? = null
    private var title: String = ""
    private var icon: Bitmap? = null
    private var webappUuid: String = ""
    private var serviceStarted = false

    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastArtworkUrl: String? = null
    private var lastHasPrevious = false
    private var lastHasNext = false
    private var jsInitiatedPause = false
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPause: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent) {
            val gen = intent.getIntExtra(MediaPlaybackService.EXTRA_GENERATION, -1)
            if (gen != -1 && gen != generation) return
            when (intent.action) {
                MediaPlaybackService.BROADCAST_PLAY -> evalJs(MediaJsBridge.RESUME_JS)

                MediaPlaybackService.BROADCAST_PAUSE -> {
                    if (jsInitiatedPause) {
                        jsInitiatedPause = false
                        return
                    }
                    evalJs(MediaJsBridge.PAUSE_ALL_JS)
                }

                MediaPlaybackService.BROADCAST_STOP -> {
                    evalJs(MediaJsBridge.PAUSE_ALL_JS)
                    serviceStarted = false
                }

                MediaPlaybackService.BROADCAST_PREVIOUS -> evalJs(MediaJsBridge.PREVIOUS_TRACK_JS)
                MediaPlaybackService.BROADCAST_NEXT -> evalJs(MediaJsBridge.NEXT_TRACK_JS)

                MediaPlaybackService.BROADCAST_SEEK_TO -> {
                    val seekMs = intent.getLongExtra(MediaPlaybackService.EXTRA_SEEK_POSITION_MS, 0L)
                    evalJs(String.format(Locale.US, MediaJsBridge.SEEK_TO_JS, seekMs / 1000.0))
                }
            }
        }
    }

    fun attach(webView: WebView, title: String, icon: Bitmap?, webappUuid: String) {
        this.webView = webView
        this.title = title
        this.icon = icon
        this.webappUuid = webappUuid
        registerReceiver()
    }

    fun injectPolyfill() {
        webView?.evaluateJavascript(MediaJsBridge.POLYFILL_JS, null)
    }

    fun injectObserver() {
        webView?.evaluateJavascript(MediaJsBridge.OBSERVER_JS, null)
    }

    fun setBackground(isBackground: Boolean) {
        evalJs("window._peelBackground = $isBackground;")
    }

    override fun onMediaStarted() {
        cancelPendingPause()
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_RESUME)
            return
        }
        stopOtherSlots()
        generation++
        context.startService(
            MediaPlaybackService.createStartIntent(
                context,
                title,
                icon,
                webappUuid,
                generation,
                lastTitle,
                lastArtist,
                lastArtworkUrl,
            )
        )
        serviceStarted = true
        if (lastHasPrevious || lastHasNext) {
            onActionsChanged(lastHasPrevious, lastHasNext)
        }
    }

    override fun onMediaPaused() {
        if (!serviceStarted) return
        cancelPendingPause()
        pendingPause = Runnable {
            if (!serviceStarted) return@Runnable
            jsInitiatedPause = true
            sendAction(MediaPlaybackService.ACTION_PAUSE)
        }.also { handler.postDelayed(it, 1200L) }
    }

    override fun onMediaStopped() {
        cancelPendingPause()
        lastTitle = null
        lastArtist = null
        lastArtworkUrl = null
        if (!serviceStarted) return
        sendAction(MediaPlaybackService.ACTION_STOP)
        serviceStarted = false
    }

    override fun onMetadataChanged(title: String?, artist: String?, artworkUrl: String?) {
        if (title == null && artist == null && artworkUrl == null) return
        lastTitle = title
        lastArtist = artist
        lastArtworkUrl = artworkUrl
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_METADATA
                putExtra(MediaPlaybackService.EXTRA_TRACK_TITLE, title ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ARTIST, artist ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ARTWORK_URL, artworkUrl ?: "")
            }
        )
    }

    override fun onActionsChanged(hasPrevious: Boolean, hasNext: Boolean) {
        lastHasPrevious = hasPrevious
        lastHasNext = hasNext
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_ACTIONS
                putExtra(MediaPlaybackService.EXTRA_HAS_PREVIOUS, hasPrevious)
                putExtra(MediaPlaybackService.EXTRA_HAS_NEXT, hasNext)
            }
        )
    }

    override fun onPositionChanged(durationMs: Long, positionMs: Long, playbackRate: Float) {
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_POSITION
                putExtra(MediaPlaybackService.EXTRA_DURATION_MS, durationMs)
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, positionMs)
                putExtra(MediaPlaybackService.EXTRA_PLAYBACK_RATE, playbackRate)
            }
        )
    }

    fun release() {
        cancelPendingPause()
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_STOP)
            serviceStarted = false
        }
        unregisterReceiver()
        webView = null
    }

    private fun evalJs(js: String) {
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    private fun sendAction(action: String) {
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply { this.action = action }
        )
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(MediaPlaybackService.BROADCAST_PLAY)
            addAction(MediaPlaybackService.BROADCAST_PAUSE)
            addAction(MediaPlaybackService.BROADCAST_STOP)
            addAction(MediaPlaybackService.BROADCAST_PREVIOUS)
            addAction(MediaPlaybackService.BROADCAST_NEXT)
            addAction(MediaPlaybackService.BROADCAST_SEEK_TO)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterReceiver() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun cancelPendingPause() {
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
    }

    private fun stopOtherSlots() {
        val myClass = MediaPlaybackService.resolveServiceClass()
        val allClasses = arrayOf(
            MediaPlaybackService::class.java,
            MediaPlaybackService0::class.java,
            MediaPlaybackService1::class.java,
            MediaPlaybackService2::class.java,
            MediaPlaybackService3::class.java,
        )
        for (cls in allClasses) {
            if (cls != myClass) {
                try {
                    context.startService(
                        Intent(context, cls).apply { action = MediaPlaybackService.ACTION_STOP }
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
