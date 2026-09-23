package wtf.mazy.peel.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
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

/**
 * Bridges GeckoView MediaSession events to Media3 notification lifecycle.
 *
 * onStop and onDeactivated schedule a deferred teardown (cancelled by onActivated /
 * onPlay) to handle web players that destroy their audio element without calling
 * setActive(false) — leaving an otherwise-stale notification with broken play action.
 * onPause never schedules teardown; the element stays resumable.
 */
class MediaPlaybackManager(
    context: Context,
    private val backgroundPlayback: Boolean,
    private val onOrientationRequest: ((Int) -> Unit)? = null,
) : GeckoMediaSession.Delegate {

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
                    MediaPlaybackService.BROADCAST_PLAY -> {
                        val live = mediaSession
                        if (live != null) {
                            live.play()
                        } else {
                            sendAction(MediaPlaybackService.ACTION_PAUSE)
                        }
                    }

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
        if (backgroundPlayback && !receiverRegistered) registerReceiver()
    }

    override fun onActivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingDeactivation()
    }

    /**
     * Sites that never call `screen.orientation.lock()` only surface fullscreen video through
     * this callback, so the video's own shape is the sole orientation signal available.
     */
    override fun onFullscreen(
        session: GeckoSession,
        mediaSession: GeckoMediaSession,
        enabled: Boolean,
        meta: GeckoMediaSession.ElementMetadata?,
    ) {
        val host = onOrientationRequest ?: return
        if (!enabled) {
            host(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            return
        }
        // Exit also arrives with a non-null meta carrying zeroed dimensions.
        if (meta == null || meta.width <= 0L || meta.height <= 0L) return
        host(
            if (meta.height > meta.width) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        )
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
        if (this.mediaSession === mediaSession) {
            this.mediaSession = null
        }
        if (!serviceStarted) return
        scheduleDeactivation()
    }

    override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) {
        this.mediaSession = mediaSession
        cancelPendingDeactivation()
        if (!backgroundPlayback) return
        if (serviceStarted) {
            sendAction(MediaPlaybackService.ACTION_RESUME)
            return
        }
        generation++
        val startIntent = MediaPlaybackService.createStartIntent(
            context, title, icon, webappUuid, generation, contentIntent,
        )
        try {
            ContextCompat.startForegroundService(context, startIntent)
            serviceStarted = true
        } catch (_: IllegalStateException) {
            serviceStarted = false
        } catch (_: SecurityException) {
            serviceStarted = false
        }
        if (!serviceStarted) MediaPlaybackService.reclaimStashedBitmap(startIntent)
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
        scheduleDeactivation()
    }

    private fun scheduleDeactivation() {
        pendingDeactivation = true
        mainHandler.removeCallbacks(deactivationTimeout)
        mainHandler.postDelayed(deactivationTimeout, MediaPlaybackService.AUTOPLAY_GAP_WAKE_MS)
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
        startService(
            Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_UPDATE_METADATA
                putExtra(MediaPlaybackService.EXTRA_TRACK_TITLE, meta.title ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ARTIST, meta.artist ?: "")
                putExtra(MediaPlaybackService.EXTRA_TRACK_ALBUM, meta.album ?: "")
            })
        meta.artwork?.getBitmap(ARTWORK_SIZE)?.accept { bitmap ->
            if (bitmap != null && serviceStarted) {
                startService(
                    Intent(context, MediaPlaybackService::class.java).apply {
                        action = MediaPlaybackService.ACTION_UPDATE_ARTWORK
                        putExtra(
                            MediaPlaybackService.EXTRA_BITMAP_ID,
                            MediaPlaybackService.stashBitmap(bitmap),
                        )
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
        startService(
            Intent(context, MediaPlaybackService::class.java).apply {
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
        startService(
            Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_UPDATE_ACTIONS
                putExtra(MediaPlaybackService.EXTRA_HAS_PREVIOUS, hasPrevious)
                putExtra(MediaPlaybackService.EXTRA_HAS_NEXT, hasNext)
            })
    }

    private fun sendAction(action: String) {
        startService(
            Intent(context, MediaPlaybackService::class.java).apply {
                this.action = action
            })
    }

    private fun startService(intent: Intent) {
        try {
            context.startService(intent)
        } catch (_: IllegalStateException) {
            serviceStarted = false
            MediaPlaybackService.reclaimStashedBitmap(intent)
        } catch (_: SecurityException) {
            serviceStarted = false
            MediaPlaybackService.reclaimStashedBitmap(intent)
        }
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
    }
}
