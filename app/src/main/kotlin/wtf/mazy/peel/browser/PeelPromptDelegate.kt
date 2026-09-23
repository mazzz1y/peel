package wtf.mazy.peel.browser

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.dialog.DateTimePickerRequest
import wtf.mazy.peel.ui.dialog.DateTimePickerType
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.deleteFilesOlderThan
import java.io.File

class PeelPromptDelegate(private val host: SessionHost) : GeckoSession.PromptDelegate {

    private val autoAuthAttempted = mutableSetOf<String>()
    private val proxyAuthAttempted = mutableSetOf<String>()

    fun clearAutoAuth() {
        autoAuthAttempted.clear()
        proxyAuthAttempted.clear()
    }

    private fun GeckoSession.PromptDelegate.BasePrompt.guardEngineDismiss(
        dialog: AlertDialog,
        result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    ) {
        setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(closed: GeckoSession.PromptDelegate.BasePrompt) {
                dialog.dismiss()
                if (!closed.isComplete) result.complete(closed.dismiss())
            }
        })
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            val dialog = MaterialAlertDialogBuilder(host.hostContext)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.dismiss())
                }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            prompt.guardEngineDismiss(dialog, result)
        }
        return result
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            val dialog = MaterialAlertDialogBuilder(host.hostContext)
                .setMessage(R.string.beforeunload_message)
                .setPositiveButton(R.string.beforeunload_leave) { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                }
                .setNegativeButton(R.string.beforeunload_stay) { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
                .setOnCancelListener { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                .show()
            prompt.guardEngineDismiss(dialog, result)
        }
        return result
    }

    override fun onButtonPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ButtonPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            val dialog = MaterialAlertDialogBuilder(host.hostContext)
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
            prompt.guardEngineDismiss(dialog, result)
        }
        return result
    }

    override fun onTextPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.TextPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.runOnUi {
            val dialog = host.showTextPromptDialog(
                title = prompt.title,
                message = prompt.message,
                defaultValue = prompt.defaultValue,
                onResult = { value -> result.complete(prompt.confirm(value)) },
                onCancel = { result.complete(prompt.dismiss()) },
            )
            prompt.guardEngineDismiss(dialog, result)
        }
        return result
    }

    override fun onAuthPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AuthPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val settings = host.effectiveSettings
        val authUri = prompt.authOptions.uri ?: ""
        val flags = prompt.authOptions.flags
        val isProxyAuth =
            (flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PROXY) != 0
        val isPreviousFailed =
            (flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED) != 0

        if (isProxyAuth) {
            val proxy = resolveActiveProxy()
            val username = proxy?.username.orEmpty()
            val password = proxy?.password.orEmpty()
            val challengeKey = "proxy:${proxy?.uuid ?: "?"}"
            if (isPreviousFailed) {
                val ctx = host.hostContext
                NotificationUtils.showToastSafe(ctx, ctx.getString(R.string.proxy_auth_failed))
            } else if (username.isNotEmpty() && proxyAuthAttempted.add(challengeKey)) {
                return GeckoResult.fromValue(prompt.confirm(username, password))
            }
        } else if (settings.isUseBasicAuth == true) {
            val username = settings.basicAuthUsername.orEmpty()
            val password = settings.basicAuthPassword.orEmpty()
            val challengeKey = authUri
            if (username.isNotEmpty() && autoAuthAttempted.add(challengeKey)) {
                return GeckoResult.fromValue(prompt.confirm(username, password))
            }
        }

        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val dialog = host.showHttpAuthDialog(
            onResult = { username, password ->
                if (!prompt.isComplete) result.complete(prompt.confirm(username, password))
            },
            onCancel = { if (!prompt.isComplete) result.complete(prompt.dismiss()) },
            url = authUri,
        )
        prompt.guardEngineDismiss(dialog, result)
        return result
    }

    private fun resolveActiveProxy(): wtf.mazy.peel.model.Proxy? {
        val dm = DataManager.instance
        val uuid = host.webAppUuid ?: return null
        val webapp = dm.getWebApp(uuid) ?: return null
        val proxyUuid = webapp.resolveProxyUuid() ?: return null
        return dm.getProxy(proxyUuid)
    }

    override fun onChoicePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.ChoicePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()

        val labels = mutableListOf<CharSequence>()
        val choices = mutableListOf<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
        val enabled = mutableListOf<Boolean>()
        fun walk(items: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>, inGroup: Boolean) {
            for (c in items) {
                if (c.separator) continue
                val nested = c.items
                if (nested != null) {
                    if (c.label.isNotEmpty()) {
                        val header = SpannableString(c.label).apply {
                            setSpan(
                                StyleSpan(android.graphics.Typeface.BOLD),
                                0,
                                length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        labels.add(header)
                        choices.add(c)
                        enabled.add(false)
                    }
                    walk(nested, inGroup = true)
                } else {
                    labels.add(if (inGroup) "  ${c.label}" else c.label)
                    choices.add(c)
                    enabled.add(!c.disabled)
                }
            }
        }
        walk(prompt.choices, inGroup = false)

        if (choices.isEmpty()) {
            result.complete(prompt.dismiss())
            return result
        }

        host.runOnUi {
            var settled = false
            val labelArray: Array<CharSequence> = labels.toTypedArray()
            val builder = MaterialAlertDialogBuilder(host.hostContext)
                .setTitle(prompt.title)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { if (!settled) result.complete(prompt.dismiss()) }

            when (prompt.type) {
                GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE -> {
                    val checked = BooleanArray(choices.size) { i ->
                        enabled[i] && choices[i].selected
                    }
                    builder
                        .setMultiChoiceItems(labelArray, checked) { dialog, which, _ ->
                            if (!enabled[which]) {
                                checked[which] = false
                                (dialog as AlertDialog).listView.setItemChecked(which, false)
                            }
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            settled = true
                            val picked = choices.filterIndexed { i, _ -> checked[i] && enabled[i] }
                            result.complete(prompt.confirm(picked.toTypedArray()))
                        }
                        .show()
                }

                else -> {
                    val initial =
                        choices.indices.firstOrNull { enabled[it] && choices[it].selected } ?: -1
                    builder
                        .setSingleChoiceItems(labelArray, initial) { dialog, which ->
                            if (!enabled[which]) {
                                (dialog as AlertDialog).listView.setItemChecked(which, false)
                                if (initial >= 0) dialog.listView.setItemChecked(initial, true)
                                return@setSingleChoiceItems
                            }
                            settled = true
                            result.complete(prompt.confirm(choices[which]))
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }
        return result
    }

    override fun onDateTimePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.DateTimePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val type = when (prompt.type) {
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> DateTimePickerType.DATE
            GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH -> DateTimePickerType.MONTH
            GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK -> DateTimePickerType.WEEK
            GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> DateTimePickerType.TIME
            else -> DateTimePickerType.DATETIME_LOCAL
        }

        host.runOnUi {
            val picker = host.showDateTimePicker(
                DateTimePickerRequest(
                    type = type,
                    value = prompt.defaultValue,
                    min = prompt.minValue,
                    max = prompt.maxValue,
                ),
                onResult = { value ->
                    if (!prompt.isComplete) result.complete(prompt.confirm(value))
                },
                onCancel = {
                    if (!prompt.isComplete) result.complete(prompt.dismiss())
                },
            )
            prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(closed: GeckoSession.PromptDelegate.BasePrompt) {
                    picker.dismiss()
                    if (!closed.isComplete) result.complete(closed.dismiss())
                }
            })
        }
        return result
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val context = host.hostContext
        val mimeTypes = prompt.mimeTypes ?: emptyArray()
        val isMultiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE

        host.filePathCallback = { uris ->
            if (!prompt.isComplete) {
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
        }
        prompt.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(closed: GeckoSession.PromptDelegate.BasePrompt) {
                host.filePathCallback = null
                host.pendingCaptureFile = null
                if (!closed.isComplete) result.complete(closed.dismiss())
            }
        })

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
                        host.pendingCaptureFile = null
                        if (!prompt.isComplete) result.complete(prompt.dismiss())
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
                host.pendingCaptureFile = null
                if (!prompt.isComplete) result.complete(prompt.dismiss())
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
        val context = host.hostContext
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        capturesDir.deleteFilesOlderThan()
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
        host.pendingCaptureFile = photoFile
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            clipData = ClipData.newRawUri(null, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
