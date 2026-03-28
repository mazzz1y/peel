package wtf.mazy.peel.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession as GeckoMediaSession
import java.io.ByteArrayOutputStream

fun Bitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}

class MediaPlaybackManager(context: Context) : GeckoMediaSession.Delegate {

    private val context: Context = context.applicationContext
    private var session: GeckoSession? = null
    private var mediaSession: GeckoMediaSession? = null
    private var title: String = ""
    private var icon: Bitmap? = null
    private var webappUuid: String = ""
    private var serviceStarted = false
    private var receiverRegistered = false

    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastHasPrevious = false
    private var lastHasNext = false
    private var nativePause = false
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPause: Runnable? = null

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent) {
                val uuid = intent.getStringExtra(MediaPlaybackService.EXTRA_WEBAPP_UUID)
                if (uuid != null && uuid != webappUuid) return
                val gen = intent.getIntExtra(MediaPlaybackService.EXTRA_GENERATION, -1)
                if (gen != -1 && gen != generation) return
                when (intent.action) {
                    MediaPlaybackService.BROADCAST_PLAY -> mediaSession?.play()
                    MediaPlaybackService.BROADCAST_PAUSE -> {
                        if (nativePause) {
                            nativePause = false
                            return
                        }
                        mediaSession?.pause()
                    }

                    MediaPlaybackService.BROADCAST_STOP -> {
                        mediaSession?.stop()
                        serviceStarted = false
                    }

                    MediaPlaybackService.BROADCAST_PREVIOUS -> mediaSession?.previousTrack()
                    MediaPlaybackService.BROADCAST_NEXT -> mediaSession?.nextTrack()
                    MediaPlaybackService.BROADCAST_SEEK_TO -> {
                        val seekMs =
                            intent.getLongExtra(MediaPlaybackService.EXTRA_SEEK_POSITION_MS, 0L)
                        mediaSession?.seekTo(seekMs / 1000.0, false)
                    }
                }
            }
        }

    fun attach(geckoSession: GeckoSession, title: String, icon: Bitmap?, webappUuid: String) {
        this.session = geckoSession
        this.title = title
        this.icon = icon
        this.webappUuid = webappUuid
        geckoSession.mediaSessionDelegate = this
        if (!receiverRegistered) registerReceiver()
    }

    override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingPause()
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_RESUME)
            return
        }
        generation++
        context.startService(
            MediaPlaybackService.createStartIntent(
                context, title, icon, webappUuid, generation,
                lastTitle, lastArtist,
            )
        )
        serviceStarted = true
        if (lastHasPrevious || lastHasNext) {
            updateActions(lastHasPrevious, lastHasNext)
        }
    }

    override fun onPause(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        if (!serviceStarted) return
        cancelPendingPause()
        pendingPause = Runnable {
            if (!serviceStarted) return@Runnable
            nativePause = true
            sendAction(MediaPlaybackService.ACTION_PAUSE)
        }.also { handler.postDelayed(it, 1200L) }
    }

    override fun onStop(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingPause()
        lastTitle = null
        lastArtist = null
        if (!serviceStarted) return
        sendAction(MediaPlaybackService.ACTION_STOP)
        serviceStarted = false
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: GeckoMediaSession,
        meta: GeckoMediaSession.Metadata
    ) {
        this.mediaSession = mediaSession
        lastTitle = meta.title?.takeIf { it.isNotEmpty() }
        lastArtist = meta.artist?.takeIf { it.isNotEmpty() }
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_METADATA
                putExtra(MediaPlaybackService.EXTRA_TRACK_TITLE, lastTitle ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ARTIST, lastArtist ?: "")
            })
        meta.artwork?.getBitmap(ARTWORK_SIZE)?.accept { bitmap ->
            if (bitmap != null && serviceStarted) {
                val bytes = bitmap.toPngBytes()
                context.startService(
                    Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                        action = MediaPlaybackService.ACTION_UPDATE_ARTWORK
                        putExtra(MediaPlaybackService.EXTRA_TRACK_ARTWORK_BYTES, bytes)
                    })
            }
        }
    }

    override fun onFeatures(
        session: GeckoSession,
        mediaSession: GeckoMediaSession,
        features: Long
    ) {
        this.mediaSession = mediaSession
        val hasPrevious = (features and GeckoMediaSession.Feature.PREVIOUS_TRACK) != 0L
        val hasNext = (features and GeckoMediaSession.Feature.NEXT_TRACK) != 0L
        updateActions(hasPrevious, hasNext)
    }

    override fun onPositionState(
        session: GeckoSession,
        mediaSession: GeckoMediaSession,
        state: GeckoMediaSession.PositionState
    ) {
        this.mediaSession = mediaSession
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_POSITION
                putExtra(MediaPlaybackService.EXTRA_DURATION_MS, (state.duration * 1000).toLong())
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, (state.position * 1000).toLong())
                putExtra(MediaPlaybackService.EXTRA_PLAYBACK_RATE, state.playbackRate.toFloat())
            })
    }

    fun release() {
        cancelPendingPause()
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_STOP)
            serviceStarted = false
        }
        unregisterReceiver()
        session?.mediaSessionDelegate = null
        session = null
        mediaSession = null
    }

    private fun updateActions(hasPrevious: Boolean, hasNext: Boolean) {
        lastHasPrevious = hasPrevious
        lastHasNext = hasNext
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_ACTIONS
                putExtra(MediaPlaybackService.EXTRA_HAS_PREVIOUS, hasPrevious)
                putExtra(MediaPlaybackService.EXTRA_HAS_NEXT, hasNext)
            })
    }

    private fun sendAction(action: String) {
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                this.action = action
            })
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
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun cancelPendingPause() {
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
    }

    companion object {
        private const val ARTWORK_SIZE = 256
    }
}
