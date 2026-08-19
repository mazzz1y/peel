package wtf.mazy.peel.browser

import android.Manifest
import android.os.Build
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.ContentPermissionStore
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.AppPrefs
import wtf.mazy.peel.util.prettyHostLabel
import wtf.mazy.peel.util.withBoldSpan

class PeelPermissionDelegate(private val host: SessionHost) : GeckoSession.PermissionDelegate {

    private val memory = SessionPermissionMemory()

    fun clearPagePermissions() {
        memory.clearPage()
    }

    fun clearSessionPermissions() {
        memory.clearSession()
    }

    private val trimmedName: String
        get() = prettyHostLabel(host.webAppName).take(MAX_NAME_LENGTH)

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        val type = perm.permission
        val reply: (Boolean) -> Unit = { granted ->
            result.complete(ContentPermissionStore.valueFor(type, granted))
        }

        when (type) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> handleTriState(
                host.effectiveSettings.isAllowLocationAccess,
                listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                PERM_KEY_LOCATION,
                R.string.permission_prompt_location,
                perm.uri,
                allowRemember = true,
                onResult = reply,
            )

            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                reply(host.effectiveSettings.isDrmAllowed == true)

            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                if (AppPrefs.isPushEnabled(host.hostWindow.context)) {
                    handleTriState(
                        WebAppSettings.PERMISSION_ASK,
                        notificationOsPermissions(),
                        PERM_KEY_NOTIFICATION,
                        R.string.permission_prompt_notifications,
                        perm.uri,
                        onResult = reply,
                    )
                } else {
                    result.complete(ContentPermissionStore.UNDECIDED)
                }
            }

            else -> reply(false)
        }

        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        val pending = mutableListOf<PendingMediaPermission>()

        if (!video.isNullOrEmpty()) {
            pending.add(
                PendingMediaPermission(
                    host.effectiveSettings.isCameraPermission,
                    listOf(Manifest.permission.CAMERA),
                    PERM_KEY_CAMERA,
                    R.string.permission_prompt_camera,
                    video.first(),
                    isVideo = true,
                )
            )
        }
        if (!audio.isNullOrEmpty()) {
            pending.add(
                PendingMediaPermission(
                    host.effectiveSettings.isMicrophonePermission,
                    listOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                    ),
                    PERM_KEY_MICROPHONE,
                    R.string.permission_prompt_microphone,
                    audio.first(),
                    isVideo = false,
                )
            )
        }

        resolveMediaPermissions(pending, 0, uri, null, null, callback)
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        if (permissions.isNullOrEmpty()) {
            callback.reject()
            return
        }
        val perms = permissions.toList().toTypedArray()
        if (host.hasPermissions(*perms)) {
            callback.grant()
            return
        }
        host.requestOsPermissions(perms) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    private data class PendingMediaPermission(
        val state: Int?,
        val androidPermissions: List<String>,
        val key: Int,
        val promptResId: Int,
        val source: GeckoSession.PermissionDelegate.MediaSource,
        val isVideo: Boolean,
    )

    private fun resolveMediaPermissions(
        pending: List<PendingMediaPermission>,
        index: Int,
        origin: String,
        grantedVideo: GeckoSession.PermissionDelegate.MediaSource?,
        grantedAudio: GeckoSession.PermissionDelegate.MediaSource?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        if (index >= pending.size) {
            if (grantedVideo != null || grantedAudio != null) {
                callback.grant(grantedVideo, grantedAudio)
            } else {
                callback.reject()
            }
            return
        }

        val p = pending[index]
        handleTriState(
            p.state,
            p.androidPermissions,
            p.key,
            p.promptResId,
            origin,
            allowRemember = true,
        ) { granted ->
            val nextVideo = if (granted && p.isVideo) p.source else grantedVideo
            val nextAudio = if (granted && !p.isVideo) p.source else grantedAudio
            resolveMediaPermissions(pending, index + 1, origin, nextVideo, nextAudio, callback)
        }
    }

    private fun handleTriState(
        state: Int?,
        androidPermissions: List<String>,
        key: Int,
        promptResId: Int,
        origin: String,
        allowRemember: Boolean = false,
        onResult: (Boolean) -> Unit,
    ) {
        val remembered = if (state == WebAppSettings.PERMISSION_ASK) {
            memory.remembered(origin, key)
        } else null
        when {
            state == WebAppSettings.PERMISSION_OFF -> onResult(false)
            remembered == false -> onResult(false)
            state == WebAppSettings.PERMISSION_ON || remembered == true -> {
                ensureOsPermission(androidPermissions, onResult)
            }

            state == WebAppSettings.PERMISSION_ASK -> {
                ensureOsPermission(androidPermissions) { osGranted ->
                    if (!osGranted) {
                        onResult(false)
                        return@ensureOsPermission
                    }
                    host.showPermissionDialog(
                        host.hostResources.getString(promptResId, trimmedName)
                            .withBoldSpan(trimmedName),
                        allowRemember,
                    ) { result, remember ->
                        val granted = result == PermissionResult.ALLOW
                        memory.remember(origin, key, granted, forSession = remember)
                        onResult(granted)
                    }
                }
            }
        }
    }

    private fun ensureOsPermission(
        androidPermissions: List<String>,
        onDone: (granted: Boolean) -> Unit,
    ) {
        val perms = androidPermissions.toTypedArray()
        if (host.hasPermissions(*perms)) {
            onDone(true)
        } else {
            host.requestOsPermissions(perms, onDone)
        }
    }

    private fun notificationOsPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    companion object {
        private const val MAX_NAME_LENGTH = 30
        private const val PERM_KEY_LOCATION = 1
        private const val PERM_KEY_CAMERA = 2
        private const val PERM_KEY_MICROPHONE = 3
        private const val PERM_KEY_NOTIFICATION = 4
    }
}
