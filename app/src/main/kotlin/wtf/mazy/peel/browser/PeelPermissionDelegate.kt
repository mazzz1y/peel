package wtf.mazy.peel.browser

import android.Manifest
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings

class PeelPermissionDelegate(private val host: SessionHost) : GeckoSession.PermissionDelegate {

    private val pageGranted = mutableSetOf<Int>()
    private val pageDenied = mutableSetOf<Int>()

    fun clearPagePermissions() {
        pageGranted.clear()
        pageDenied.clear()
    }

    private val trimmedName: String
        get() = host.webAppName.take(MAX_NAME_LENGTH)

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int>? {
        val result = GeckoResult<Int>()

        when (perm.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                handleTriState(
                    host.effectiveSettings.isAllowLocationAccess,
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                    PERM_KEY_LOCATION,
                    R.string.permission_prompt_location,
                ) { granted ->
                    result.complete(
                        if (granted) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
            }

            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                if (host.effectiveSettings.isDrmAllowed == true) {
                    result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                } else {
                    result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                }
            }

            else -> result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
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

        if (video != null && video.isNotEmpty()) {
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
        if (audio != null && audio.isNotEmpty()) {
            pending.add(
                PendingMediaPermission(
                    host.effectiveSettings.isMicrophonePermission,
                    listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS),
                    PERM_KEY_MICROPHONE,
                    R.string.permission_prompt_microphone,
                    audio.first(),
                    isVideo = false,
                )
            )
        }

        resolveMediaPermissions(pending, 0, null, null, callback)
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        if (permissions == null || permissions.isEmpty()) {
            callback.reject()
            return
        }
        val perms = permissions.map { it }.toTypedArray()
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
        handleTriState(p.state, p.androidPermissions, p.key, p.promptResId) { granted ->
            val nextVideo = if (granted && p.isVideo) p.source else grantedVideo
            val nextAudio = if (granted && !p.isVideo) p.source else grantedAudio
            resolveMediaPermissions(pending, index + 1, nextVideo, nextAudio, callback)
        }
    }

    private fun handleTriState(
        state: Int?,
        androidPermissions: List<String>,
        key: Int,
        promptResId: Int,
        onResult: (Boolean) -> Unit,
    ) {
        when {
            key in pageDenied -> onResult(false)
            state == WebAppSettings.PERMISSION_ON || key in pageGranted -> {
                ensureOsPermission(androidPermissions) { granted ->
                    if (granted) pageGranted.add(key)
                    onResult(granted)
                }
            }
            state == WebAppSettings.PERMISSION_ASK -> {
                ensureOsPermission(androidPermissions) { osGranted ->
                    if (!osGranted) {
                        onResult(false)
                        return@ensureOsPermission
                    }
                    host.showPermissionDialog(
                        host.getString(promptResId, trimmedName)
                    ) { result ->
                        when (result) {
                            PermissionResult.ALLOW -> {
                                pageGranted.add(key)
                                onResult(true)
                            }
                            PermissionResult.DENY -> {
                                pageDenied.add(key)
                                onResult(false)
                            }
                        }
                    }
                }
            }
            else -> onResult(false)
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

    companion object {
        private const val MAX_NAME_LENGTH = 30
        private const val PERM_KEY_LOCATION = 1
        private const val PERM_KEY_CAMERA = 2
        private const val PERM_KEY_MICROPHONE = 3
    }
}
