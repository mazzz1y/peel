package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.serialization.json.Json
import wtf.mazy.peel.browser.PopupSessionHolder
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings

class PopupActivity : SessionPageActivity() {

    private lateinit var snapshotSettings: WebAppSettings
    override val effectiveSettings: WebAppSettings
        get() = snapshotSettings

    override val sessionContextId: String?
        get() = intent.getStringExtra(EXTRA_CONTEXT_ID)
    override val sessionPrivateMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_PRIVATE_MODE, false)

    override val retainSessionAcrossRecreation = true

    override fun onCreate(savedInstanceState: Bundle?) {
        snapshotSettings = intent.getStringExtra(EXTRA_SETTINGS)
            ?.let { runCatching { Json.decodeFromString<WebAppSettings>(it) }.getOrNull() }
            ?: DataManager.instance.defaultSettings.settings
        super.onCreate(savedInstanceState)
    }

    override fun onSessionHostReady() {
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)
        val key = intent.getStringExtra(EXTRA_SESSION_KEY) ?: run { finish(); return }
        val popup = PopupSessionHolder.take(key) ?: run { finish(); return }
        connectSession(popup)
        displaySession(popup)
    }

    override fun onProcessKilled() = finish()

    override fun onContentCrashed() = finish()

    companion object {
        const val EXTRA_SESSION_KEY = "popup_session_key"
        const val EXTRA_TITLE = "popup_title"
        const val EXTRA_SETTINGS = "popup_settings"
        const val EXTRA_CONTEXT_ID = "popup_context_id"
        const val EXTRA_PRIVATE_MODE = "popup_private_mode"

        fun intentFor(
            context: Context,
            key: String,
            title: String,
            settings: WebAppSettings,
            contextId: String?,
            privateMode: Boolean,
        ): Intent {
            return Intent(context, PopupActivity::class.java)
                .putExtra(EXTRA_SESSION_KEY, key)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SETTINGS, Json.encodeToString(settings))
                .putExtra(EXTRA_CONTEXT_ID, contextId)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode)
        }
    }
}
