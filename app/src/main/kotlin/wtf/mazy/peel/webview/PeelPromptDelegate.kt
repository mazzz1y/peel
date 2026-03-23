package wtf.mazy.peel.webview

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings

class PeelPromptDelegate(private val host: SessionHost) : GeckoSession.PromptDelegate {

    private val autoAuthAttempted = mutableSetOf<String>()

    fun clearAutoAuth() {
        autoAuthAttempted.clear()
    }

    override fun onAlertPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.AlertPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
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
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
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
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
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
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
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
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        host.filePathCallback = { uris ->
            if (uris != null && uris.isNotEmpty()) {
                result.complete(prompt.confirm(host.hostWindow.context, uris.first()!!))
            } else {
                result.complete(prompt.dismiss())
            }
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (prompt.mimeTypes != null && prompt.mimeTypes!!.isNotEmpty()) {
                prompt.mimeTypes!!.first()
            } else {
                "*/*"
            }
        }

        if (!host.launchFilePicker(intent)) {
            host.filePathCallback = null
            result.complete(prompt.dismiss())
        }
        return result
    }
}
