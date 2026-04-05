package wtf.mazy.peel.browser

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.io.File

class PeelPromptDelegate(private val host: SessionHost) : GeckoSession.PromptDelegate {

    private val autoAuthAttempted = mutableSetOf<String>()

    fun clearAutoAuth() {
        autoAuthAttempted.clear()
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            MaterialAlertDialogBuilder(host.hostWindow.context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.dismiss())
                }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
        }
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            MaterialAlertDialogBuilder(host.hostWindow.context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                }
                .setOnCancelListener {
                    result.complete(prompt.dismiss())
                }
                .show()
        }
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            val input = android.widget.EditText(host.hostWindow.context).apply {
                setText(prompt.defaultValue ?: "")
            }
            MaterialAlertDialogBuilder(host.hostWindow.context)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(input.text.toString()))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    result.complete(prompt.dismiss())
                }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
        }
        return result
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AuthPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val settings = host.effectiveSettings
        val authUri = prompt.authOptions.uri ?: ""
        if (settings.isUseBasicAuth == true) {
            val username = settings.basicAuthUsername.orEmpty()
            val password = settings.basicAuthPassword.orEmpty()
            val challengeKey = authUri
            if (username.isNotEmpty() && autoAuthAttempted.add(challengeKey)) {
                return GeckoResult.fromValue(prompt.confirm(username, password))
            }
        }

        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.showHttpAuthDialog(
            onResult = { username, password ->
                result.complete(prompt.confirm(username, password))
            },
            onCancel = { result.complete(prompt.dismiss()) },
            host = authUri,
            realm = null,
        )
        return result
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val context = host.hostWindow.context
        val mimeTypes = prompt.mimeTypes ?: emptyArray()
        val isMultiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE

        host.filePathCallback = { uris ->
            if (!uris.isNullOrEmpty()) {
                if (isMultiple) {
                    result.complete(prompt.confirm(context, uris))
                } else {
                    result.complete(prompt.confirm(context, uris.first()))
                }
            } else {
                result.complete(prompt.dismiss())
            }
        }

        fun matchesAny(prefix: String) =
            mimeTypes.isEmpty() || mimeTypes.any { it.startsWith(prefix) || it == "*/*" }

        fun matchesAll(prefix: String) =
            mimeTypes.isNotEmpty() && mimeTypes.all { it.startsWith(prefix) }

        val wantsCapture = matchesAny("image/") || matchesAny("video/")
        val hasCameraPermission = host.hasPermissions(Manifest.permission.CAMERA)

        fun launchWithCapture(canCapture: Boolean) {
            if (prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE) {
                val directIntent = when {
                    matchesAll("image/") && canCapture -> buildImageCaptureIntent()
                    matchesAll("video/") && canCapture -> Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    matchesAll("audio/") -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    else -> null
                }
                if (directIntent != null) {
                    if (!host.launchFilePicker(directIntent)) {
                        host.filePathCallback = null
                        result.complete(prompt.dismiss())
                    }
                    return
                }
            }

            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (mimeTypes.isNotEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                if (isMultiple) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            val extras = mutableListOf<Intent>()
            if (canCapture) {
                if (matchesAny("image/")) {
                    buildImageCaptureIntent()?.let { extras.add(it) }
                }
                if (matchesAny("video/")) {
                    extras.add(Intent(MediaStore.ACTION_VIDEO_CAPTURE))
                }
            }
            if (matchesAny("audio/")) {
                extras.add(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
            }

            val launchIntent = if (extras.isNotEmpty()) {
                Intent.createChooser(contentIntent, null).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
                }
            } else {
                contentIntent
            }

            if (!host.launchFilePicker(launchIntent)) {
                host.filePathCallback = null
                result.complete(prompt.dismiss())
            }
        }

        if (wantsCapture && !hasCameraPermission) {
            host.requestOsPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                launchWithCapture(granted)
            }
        } else {
            launchWithCapture(hasCameraPermission)
        }

        return result
    }

    private fun buildImageCaptureIntent(): Intent? {
        val context = host.hostWindow.context
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        capturesDir.listFiles()?.forEach { it.delete() }
        val photoFile = try {
            File.createTempFile("img_", ".jpg", capturesDir)
        } catch (_: Exception) {
            return null
        }

        val photoUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile,
        )
        captureFile = photoFile
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            clipData = ClipData.newRawUri(null, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private var captureFile: File? = null

        fun consumeCaptureUri(): Uri? {
            val file = captureFile
            captureFile = null
            return file?.let { Uri.fromFile(it) }
        }
    }
}
