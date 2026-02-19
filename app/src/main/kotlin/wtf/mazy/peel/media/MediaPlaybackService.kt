package wtf.mazy.peel.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.util.WebViewLauncher
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
open class MediaPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var peelPlayer: PeelPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var artworkJob: Job? = null
    private var sessionAdded = false

    private var appTitle = ""
    private var appIcon: Bitmap? = null

    private var trackTitle: String? = null
    private var trackArtist: String? = null
    private var trackArtwork: Bitmap? = null
    private var trackArtworkUrl: String? = null

    private var playing = false
    private var hasPrevious = false
    private var hasNext = false
    private var durationMs = 0L
    private var positionMs = 0L
    private var playbackRate = 1f
    private var positionUpdateTime = 0L
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.media_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )
        createNotificationChannel()
        val p = PeelPlayer(Looper.getMainLooper())
        peelPlayer = p
        session = MediaSession.Builder(this, p)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_RESUME -> setPlaying(true)
            ACTION_PAUSE -> {
                broadcast(BROADCAST_PAUSE)
                setPlaying(false)
            }

            ACTION_STOP -> {
                broadcast(BROADCAST_STOP)
                stopPlayback()
            }

            ACTION_UPDATE_METADATA -> handleUpdateMetadata(intent)
            ACTION_UPDATE_ACTIONS -> {
                hasPrevious = intent.getBooleanExtra(EXTRA_HAS_PREVIOUS, false)
                hasNext = intent.getBooleanExtra(EXTRA_HAS_NEXT, false)
                notifyPlayerChanged()
            }

            ACTION_UPDATE_POSITION -> {
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                playbackRate = intent.getFloatExtra(EXTRA_PLAYBACK_RATE, 1f)
                positionUpdateTime = SystemClock.elapsedRealtime()
                notifyPlayerChanged()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) {
            broadcast(BROADCAST_STOP)
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        peelPlayer = null
        scope.cancel()
        releaseWakeLocks()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        appTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        generation = intent.getIntExtra(EXTRA_GENERATION, 0)
        val iconBytes = intent.getByteArrayExtra(EXTRA_ICON)
        appIcon = iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        buildContentIntent(intent.getStringExtra(EXTRA_WEBAPP_UUID))?.let {
            session?.setSessionActivity(it)
        }

        trackTitle = intent.getStringExtra(EXTRA_TRACK_TITLE)?.takeIf { it.isNotEmpty() }
        trackArtist = intent.getStringExtra(EXTRA_TRACK_ARTIST)?.takeIf { it.isNotEmpty() }
        trackArtwork = null
        val artworkUrl = intent.getStringExtra(EXTRA_TRACK_ARTWORK_URL)?.takeIf { it.isNotEmpty() }
        trackArtworkUrl = artworkUrl
        if (artworkUrl != null) fetchArtwork(artworkUrl)

        durationMs = 0L
        positionMs = 0L
        playbackRate = 1f
        positionUpdateTime = 0L
        playing = true

        acquireWakeLocks()
        if (!sessionAdded) {
            session?.let { addSession(it) }
            sessionAdded = true
        }
        notifyPlayerChanged()
    }

    private fun handleUpdateMetadata(intent: Intent) {
        trackTitle = intent.getStringExtra(EXTRA_TRACK_TITLE)?.takeIf { it.isNotEmpty() }
        trackArtist = intent.getStringExtra(EXTRA_TRACK_ARTIST)?.takeIf { it.isNotEmpty() }
        val artworkUrl = intent.getStringExtra(EXTRA_TRACK_ARTWORK_URL)?.takeIf { it.isNotEmpty() }
        if (artworkUrl != trackArtworkUrl) {
            trackArtwork = null
            trackArtworkUrl = artworkUrl
            if (artworkUrl != null) fetchArtwork(artworkUrl)
        }
        notifyPlayerChanged()
    }

    private fun setPlaying(value: Boolean) {
        if (playing && !value && positionUpdateTime > 0) {
            val elapsed = SystemClock.elapsedRealtime() - positionUpdateTime
            positionMs += (elapsed * playbackRate).toLong()
            positionUpdateTime = SystemClock.elapsedRealtime()
        }
        playing = value
        notifyPlayerChanged()
    }

    private fun stopPlayback() {
        playing = false
        releaseWakeLocks()
        pauseAllPlayersAndStopSelf()
    }

    private fun notifyPlayerChanged() {
        peelPlayer?.notifyStateChanged()
    }

    private fun fetchArtwork(url: String) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.instanceFollowRedirects = true
                    val result = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    result
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null && trackArtworkUrl == url) {
                trackArtwork = bmp
                notifyPlayerChanged()
            }
        }
    }

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "peel:media")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "peel:media")
                .apply { acquire() }
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun buildContentIntent(uuid: String?): PendingIntent? {
        uuid ?: return null
        DataManager.instance.loadAppData()
        val webapp = DataManager.instance.getWebApp(uuid) ?: return null
        val intent = WebViewLauncher.createWebViewIntent(webapp, this) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, uuid.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.media_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.media_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun broadcast(action: String, extras: Intent? = null) {
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(EXTRA_GENERATION, generation)
            extras?.extras?.let { putExtras(it) }
        })
    }

    private inner class PeelPlayer(looper: Looper) : SimpleBasePlayer(looper) {

        fun notifyStateChanged() = invalidateState()

        override fun getState(): State {
            val displayTitle = trackTitle ?: appTitle
            val art = trackArtwork ?: appIcon

            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(displayTitle)
            trackArtist?.let { metadataBuilder.setArtist(it) }
            art?.let {
                metadataBuilder.setArtworkData(
                    bitmapToBytes(it),
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }
            val metadata = metadataBuilder.build()

            val mediaItem = MediaItem.Builder()
                .setMediaId("current")
                .setMediaMetadata(metadata)
                .build()

            val commands = Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_GET_METADATA,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                )
            if (hasPrevious) commands.add(COMMAND_SEEK_TO_PREVIOUS)
            if (hasNext) commands.add(COMMAND_SEEK_TO_NEXT)
            if (durationMs > 0) commands.add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

            return State.Builder()
                .setAvailableCommands(commands.build())
                .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(STATE_READY)
                .setContentPositionMs(positionMs)
                .setPlaylist(
                    ImmutableList.of(
                        MediaItemData.Builder("current")
                            .setMediaItem(mediaItem)
                            .setMediaMetadata(metadata)
                            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                            .build()
                    )
                )
                .setPlaybackParameters(PlaybackParameters(playbackRate))
                .setCurrentMediaItemIndex(0)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) {
                broadcast(BROADCAST_PLAY)
                setPlaying(true)
            } else {
                broadcast(BROADCAST_PAUSE)
                setPlaying(false)
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            broadcast(BROADCAST_STOP)
            stopPlayback()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            when (seekCommand) {
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    broadcast(BROADCAST_PREVIOUS)

                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                    broadcast(BROADCAST_NEXT)

                else -> {
                    this@MediaPlaybackService.positionMs = positionMs
                    this@MediaPlaybackService.positionUpdateTime = SystemClock.elapsedRealtime()
                    broadcast(
                        BROADCAST_SEEK_TO,
                        Intent().putExtra(EXTRA_SEEK_POSITION_MS, positionMs)
                    )
                }
            }
            return Futures.immediateVoidFuture()
        }
    }

    private class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                )
                .build()
    }

    companion object {
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "wtf.mazy.peel.media.START"
        const val ACTION_RESUME = "wtf.mazy.peel.media.RESUME"
        const val ACTION_PAUSE = "wtf.mazy.peel.media.PAUSE"
        const val ACTION_STOP = "wtf.mazy.peel.media.STOP"
        const val ACTION_UPDATE_METADATA = "wtf.mazy.peel.media.UPDATE_METADATA"
        const val ACTION_UPDATE_ACTIONS = "wtf.mazy.peel.media.UPDATE_ACTIONS"
        const val ACTION_UPDATE_POSITION = "wtf.mazy.peel.media.UPDATE_POSITION"

        const val BROADCAST_PLAY = "wtf.mazy.peel.media.BROADCAST_PLAY"
        const val BROADCAST_PAUSE = "wtf.mazy.peel.media.BROADCAST_PAUSE"
        const val BROADCAST_STOP = "wtf.mazy.peel.media.BROADCAST_STOP"
        const val BROADCAST_PREVIOUS = "wtf.mazy.peel.media.BROADCAST_PREVIOUS"
        const val BROADCAST_NEXT = "wtf.mazy.peel.media.BROADCAST_NEXT"
        const val BROADCAST_SEEK_TO = "wtf.mazy.peel.media.BROADCAST_SEEK_TO"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ICON = "icon"
        const val EXTRA_WEBAPP_UUID = "webapp_uuid"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ARTWORK_URL = "track_artwork_url"
        const val EXTRA_HAS_PREVIOUS = "has_previous"
        const val EXTRA_HAS_NEXT = "has_next"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_PLAYBACK_RATE = "playback_rate"
        const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"
        const val EXTRA_GENERATION = "generation"

        fun createStartIntent(
            context: Context,
            title: String,
            icon: Bitmap?,
            webappUuid: String,
            generation: Int,
            trackTitle: String? = null,
            trackArtist: String? = null,
            trackArtworkUrl: String? = null,
        ): Intent {
            return Intent(context, resolveServiceClass()).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_WEBAPP_UUID, webappUuid)
                putExtra(EXTRA_GENERATION, generation)
                if (icon != null) {
                    val stream = ByteArrayOutputStream()
                    icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    putExtra(EXTRA_ICON, stream.toByteArray())
                }
                putExtra(EXTRA_TRACK_TITLE, trackTitle ?: "")
                putExtra(EXTRA_TRACK_ARTIST, trackArtist ?: "")
                putExtra(EXTRA_TRACK_ARTWORK_URL, trackArtworkUrl ?: "")
            }
        }

        fun resolveServiceClass(): Class<out MediaPlaybackService> =
            when (SandboxManager.currentSlotId) {
                0 -> MediaPlaybackService0::class.java
                1 -> MediaPlaybackService1::class.java
                2 -> MediaPlaybackService2::class.java
                3 -> MediaPlaybackService3::class.java
                else -> MediaPlaybackService::class.java
            }

        private fun bitmapToBytes(bmp: Bitmap): ByteArray {
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            return stream.toByteArray()
        }
    }
}

class MediaPlaybackService0 : MediaPlaybackService()
class MediaPlaybackService1 : MediaPlaybackService()
class MediaPlaybackService2 : MediaPlaybackService()
class MediaPlaybackService3 : MediaPlaybackService()
