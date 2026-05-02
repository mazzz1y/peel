package wtf.mazy.peel.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import wtf.mazy.peel.R

@OptIn(UnstableApi::class)
open class MediaPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var peelPlayer: PeelPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionAdded = false

    private var appTitle = ""
    private var appIcon: Bitmap? = null
    private var appIconBytes: ByteArray? = null
    private var webappUuid: String? = null

    private var trackTitle: String? = null
    private var trackArtist: String? = null
    private var trackAlbum: String? = null
    private var trackArtworkBytes: ByteArray? = null

    private var playing = false
    private var hasPrevious = false
    private var hasNext = false
    private var durationMs = 0L
    private var positionMs = 0L
    private var playbackRate = 1f
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
        session = MediaSession.Builder(this, p).setCallback(SessionCallback()).build()
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setPlayerCommand(Player.COMMAND_STOP)
            .setDisplayName(getString(R.string.media_stop_description))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session?.setMediaButtonPreferences(ImmutableList.of(stopButton))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_RESUME -> setPlaying(true)
            ACTION_PAUSE -> setPlaying(false)
            ACTION_STOP -> stopPlayback()

            ACTION_UPDATE_METADATA -> handleUpdateMetadata(intent)
            ACTION_UPDATE_ARTWORK -> handleUpdateArtwork()
            ACTION_UPDATE_ACTIONS -> {
                hasPrevious = intent.getBooleanExtra(EXTRA_HAS_PREVIOUS, false)
                hasNext = intent.getBooleanExtra(EXTRA_HAS_NEXT, false)
                notifyPlayerChanged()
            }

            ACTION_UPDATE_POSITION -> {
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                playbackRate = intent.getFloatExtra(EXTRA_PLAYBACK_RATE, 1f)
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
        releaseWakeLocks()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        appTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        generation = intent.getIntExtra(EXTRA_GENERATION, 0)
        webappUuid = intent.getStringExtra(EXTRA_WEBAPP_UUID)
        appIcon = pendingIcon
        appIconBytes = appIcon?.toPngBytes()
        pendingIcon = null
        IntentCompat.getParcelableExtra(intent, EXTRA_CONTENT_INTENT, PendingIntent::class.java)
            ?.let { session?.setSessionActivity(it) }

        trackTitle = null
        trackArtist = null
        trackAlbum = null
        trackArtworkBytes = null

        durationMs = 0L
        positionMs = 0L
        playbackRate = 1f
        playing = true
        hasPrevious = false
        hasNext = false

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
        trackAlbum = intent.getStringExtra(EXTRA_TRACK_ALBUM)?.takeIf { it.isNotEmpty() }
        notifyPlayerChanged()
    }

    private fun handleUpdateArtwork() {
        val bmp = pendingArtwork ?: return
        pendingArtwork = null
        trackArtworkBytes = bmp.toPngBytes()
        notifyPlayerChanged()
    }

    private fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        if (value) acquireWakeLocks() else releaseWakeLocks()
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

    private fun acquireWakeLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "peel:media").apply {
                    setReferenceCounted(false)
                }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock =
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "peel:media").apply {
                    setReferenceCounted(false)
                }
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire(4 * 60 * 60 * 1000L)
        wifiLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseWakeLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply {
                    description = getString(R.string.media_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun broadcast(action: String, extras: Intent? = null) {
        sendBroadcast(
            Intent(action).apply {
                setPackage(packageName)
                putExtra(EXTRA_GENERATION, generation)
                putExtra(EXTRA_WEBAPP_UUID, webappUuid)
                extras?.extras?.let { putExtras(it) }
            })
    }

    private inner class PeelPlayer(looper: Looper) : SimpleBasePlayer(looper) {

        fun notifyStateChanged() = invalidateState()

        override fun getState(): State {
            val displayTitle = trackTitle ?: appTitle
            val artBytes = trackArtworkBytes ?: appIconBytes

            val metadataBuilder = MediaMetadata.Builder().setTitle(displayTitle)
            trackArtist?.let { metadataBuilder.setArtist(it) }
            trackAlbum?.let { metadataBuilder.setAlbumTitle(it) }
            artBytes?.let {
                metadataBuilder.setArtworkData(
                    it, MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }
            val metadata = metadataBuilder.build()

            val mediaItem =
                MediaItem.Builder().setMediaId("current").setMediaMetadata(metadata).build()

            val commands =
                Player.Commands.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_STOP,
                        COMMAND_GET_METADATA,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                    )
            val validDurationUs =
                if (durationMs in 1..Long.MAX_VALUE / 1000) durationMs * 1000 else C.TIME_UNSET

            if (hasPrevious) commands.add(COMMAND_SEEK_TO_PREVIOUS)
            if (hasNext) commands.add(COMMAND_SEEK_TO_NEXT)
            if (validDurationUs != C.TIME_UNSET) commands.add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

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
                            .setDurationUs(validDurationUs)
                            .build()
                    )
                )
                .setPlaybackParameters(
                    if (playbackRate > 0) PlaybackParameters(playbackRate)
                    else PlaybackParameters.DEFAULT
                )
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
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> broadcast(BROADCAST_PREVIOUS)

                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> broadcast(BROADCAST_NEXT)

                else -> {
                    this@MediaPlaybackService.positionMs = positionMs
                    broadcast(
                        BROADCAST_SEEK_TO, Intent().putExtra(EXTRA_SEEK_POSITION_MS, positionMs)
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
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
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
        const val ACTION_UPDATE_ARTWORK = "wtf.mazy.peel.media.UPDATE_ARTWORK"

        const val BROADCAST_PLAY = "wtf.mazy.peel.media.BROADCAST_PLAY"
        const val BROADCAST_PAUSE = "wtf.mazy.peel.media.BROADCAST_PAUSE"
        const val BROADCAST_STOP = "wtf.mazy.peel.media.BROADCAST_STOP"
        const val BROADCAST_PREVIOUS = "wtf.mazy.peel.media.BROADCAST_PREVIOUS"
        const val BROADCAST_NEXT = "wtf.mazy.peel.media.BROADCAST_NEXT"
        const val BROADCAST_SEEK_TO = "wtf.mazy.peel.media.BROADCAST_SEEK_TO"

        const val EXTRA_TITLE = "title"
        const val EXTRA_WEBAPP_UUID = "webapp_uuid"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ALBUM = "track_album"
        const val EXTRA_HAS_PREVIOUS = "has_previous"
        const val EXTRA_HAS_NEXT = "has_next"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_PLAYBACK_RATE = "playback_rate"
        const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"
        const val EXTRA_GENERATION = "generation"
        const val EXTRA_CONTENT_INTENT = "content_intent"

        @Volatile
        var pendingIcon: Bitmap? = null

        @Volatile
        var pendingArtwork: Bitmap? = null

        fun createStartIntent(
            context: Context,
            title: String,
            icon: Bitmap?,
            webappUuid: String,
            generation: Int,
            contentIntent: PendingIntent?,
        ): Intent {
            pendingIcon = icon
            return Intent(context, resolveServiceClass()).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_WEBAPP_UUID, webappUuid)
                putExtra(EXTRA_GENERATION, generation)
                putExtra(EXTRA_CONTENT_INTENT, contentIntent)
            }
        }

        fun resolveServiceClass(): Class<out MediaPlaybackService> =
            MediaPlaybackService::class.java
    }
}
