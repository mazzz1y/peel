package wtf.mazy.peel.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import java.io.ByteArrayOutputStream
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

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
    private var contentIntent: PendingIntent? = null
    private var serviceStarted = false
    private var receiverRegistered = false

    private var generation = 0

    private var pendingDeactivation = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val deactivationTimeout = Runnable {
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_STOP)
            serviceStarted = false
        }
        pendingDeactivation = false
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent) {
                val uuid = intent.getStringExtra(MediaPlaybackService.EXTRA_WEBAPP_UUID)
                if (uuid != null && uuid != webappUuid) return
                val gen = intent.getIntExtra(MediaPlaybackService.EXTRA_GENERATION, -1)
                if (gen != -1 && gen != generation) return
                when (intent.action) {
                    MediaPlaybackService.BROADCAST_PLAY -> mediaSession?.play()
                    MediaPlaybackService.BROADCAST_PAUSE -> mediaSession?.pause()

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

    fun attach(
        geckoSession: GeckoSession,
        title: String,
        icon: Bitmap?,
        webappUuid: String,
        contentIntent: PendingIntent?,
    ) {
        this.session = geckoSession
        this.title = title
        this.icon = icon
        this.webappUuid = webappUuid
        this.contentIntent = contentIntent
        geckoSession.mediaSessionDelegate = this
        if (!receiverRegistered) registerReceiver()
    }

    override fun onActivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingDeactivation()
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
        if (this.mediaSession === mediaSession) {
            this.mediaSession = null
        }
        if (!serviceStarted) return
        pendingDeactivation = true
        mainHandler.removeCallbacks(deactivationTimeout)
        mainHandler.postDelayed(deactivationTimeout, DEACTIVATION_GRACE_MS)
    }

    override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingDeactivation()
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_RESUME)
            return
        }
        generation++
        context.startService(
            MediaPlaybackService.createStartIntent(
                context, title, icon, webappUuid, generation, contentIntent,
            )
        )
        serviceStarted = true
    }

    override fun onPause(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        if (!serviceStarted) return
        sendAction(MediaPlaybackService.ACTION_PAUSE)
    }

    override fun onStop(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        if (!serviceStarted) return
        sendAction(MediaPlaybackService.ACTION_PAUSE)
        pendingDeactivation = true
        mainHandler.removeCallbacks(deactivationTimeout)
        mainHandler.postDelayed(deactivationTimeout, DEACTIVATION_GRACE_MS)
    }

    private fun cancelPendingDeactivation() {
        if (!pendingDeactivation) return
        pendingDeactivation = false
        mainHandler.removeCallbacks(deactivationTimeout)
    }

    override fun onMetadata(
        session: GeckoSession,
        mediaSession: GeckoMediaSession,
        meta: GeckoMediaSession.Metadata
    ) {
        this.mediaSession = mediaSession
        if (!serviceStarted) return
        context.startService(
            Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                action = MediaPlaybackService.ACTION_UPDATE_METADATA
                putExtra(MediaPlaybackService.EXTRA_TRACK_TITLE, meta.title ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ARTIST, meta.artist ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ALBUM, meta.album ?: "")
            })
        meta.artwork?.getBitmap(ARTWORK_SIZE)?.accept { bitmap ->
            if (bitmap != null && serviceStarted) {
                MediaPlaybackService.pendingArtwork = bitmap
                context.startService(
                    Intent(context, MediaPlaybackService.resolveServiceClass()).apply {
                        action = MediaPlaybackService.ACTION_UPDATE_ARTWORK
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
        mainHandler.removeCallbacks(deactivationTimeout)
        pendingDeactivation = false
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

    companion object {
        private const val ARTWORK_SIZE = 512
        private const val DEACTIVATION_GRACE_MS = 3_000L
    }
}
