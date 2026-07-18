package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.PeelTranslationDelegate
import wtf.mazy.peel.browser.PopupSessionHolder
import wtf.mazy.peel.browser.TranslationLanguages
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.FloatingControlsView
import wtf.mazy.peel.ui.extensions.ExtensionPickerDialog
import wtf.mazy.peel.ui.extensions.SessionExtensionActions
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.shareText

class PopupActivity : SessionPageActivity() {

    private lateinit var snapshotSettings: WebAppSettings
    private var hasPageTitle = false

    override val effectiveSettings: WebAppSettings
        get() = snapshotSettings

    override val sessionContextId: String?
        get() = intent.getStringExtra(EXTRA_CONTEXT_ID)
    override val sessionPrivateMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_PRIVATE_MODE, false)

    override val retainSessionAcrossRecreation = true

    override val ownerWebAppUuid: String?
        get() = intent.getStringExtra(EXTRA_OWNER_UUID)

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
        translationDelegate = PeelTranslationDelegate(this).also {
            it.presetManualTarget(intent.getStringExtra(EXTRA_TRANSLATE_TARGET))
            popup.translationsSessionDelegate = it
        }
        sessionExtensionActions.attach(popup)
        displaySession(popup)
        lifecycleScope.launch {
            translationsSupported = TranslationLanguages.isEngineSupported()
            if (translationsSupported) rebuildFloatingControls()
        }
    }

    override fun onDestroy() {
        sessionExtensionActions.detach()
        super.onDestroy()
    }

    override fun onLocationChanged(url: String) {
        super.onLocationChanged(url)
        translationDelegate?.onLocationChanged(url)
        hasPageTitle = false
        supportActionBar?.title = url.toUri().host ?: url
    }

    override fun onPageTitleChanged(title: String?) {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "about:blank") return
        hasPageTitle = true
        supportActionBar?.title = trimmed
    }

    override fun onProcessKilled() = finish()

    override fun onContentCrashed() = finish()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (ownerWebAppUuid != null) menuInflater.inflate(R.menu.menu_popup, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_in_app -> {
                openInOwnerApp(); true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openInOwnerApp() {
        val ownerUuid = ownerWebAppUuid ?: return
        val webapp = DataManager.instance.getWebApp(ownerUuid) ?: run {
            NotificationUtils.showToast(this, getString(R.string.browser_launch_failed))
            return
        }
        val url = lastLoadedUrl.ifBlank { null }
        BrowserLauncher.launch(webapp, this, url = url)
        finish()
    }

    override fun onSessionStarted() {
        SessionExtensionActions.setActive(sessionExtensionActions)
        showFloatingControls()
    }

    override fun onSessionStopped() {
        sessionExtensionActions.dismissPopup()
        hideFloatingControls()
    }

    override fun createFloatingControls(): FloatingControlsView {
        val translateEnabled =
            translationsSupported && effectiveSettings.isTranslatorEnabled == true
        val controls = FloatingControlsView(
            parent = findViewById(R.id.browserContent),
            webappUuid = ownerWebAppUuid ?: FLOATING_CONTROLS_KEY,
            onReload = ::reloadCurrentPage,
            onShare = { shareText(lastLoadedUrl) },
            onFind = ::openFindInPage,
            onTranslate = if (translateEnabled) ({ openTranslateDialog() }) else null,
            onExtensions = if (SessionExtensionActions.hasExtensions)
                ({ ExtensionPickerDialog.show(this, sessionExtensionActions) }) else null,
        )
        controls.setIncognito(sessionPrivateMode)
        if (translationsSupported) {
            controls.setTranslateActive(translationDelegate?.isPageTranslated == true)
        }
        return controls
    }

    companion object {
        const val EXTRA_SESSION_KEY = "popup_session_key"
        const val EXTRA_TITLE = "popup_title"
        const val EXTRA_SETTINGS = "popup_settings"
        const val EXTRA_CONTEXT_ID = "popup_context_id"
        const val EXTRA_PRIVATE_MODE = "popup_private_mode"
        const val EXTRA_OWNER_UUID = "popup_owner_uuid"
        const val EXTRA_TRANSLATE_TARGET = "popup_translate_target"

        private const val FLOATING_CONTROLS_KEY = "popup"

        fun intentFor(
            context: Context,
            key: String,
            title: String,
            settings: WebAppSettings,
            contextId: String?,
            privateMode: Boolean,
            ownerWebAppUuid: String?,
            translateTarget: String?,
        ): Intent {
            return Intent(context, PopupActivity::class.java)
                .putExtra(EXTRA_SESSION_KEY, key)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SETTINGS, Json.encodeToString(settings))
                .putExtra(EXTRA_CONTEXT_ID, contextId)
                .putExtra(EXTRA_PRIVATE_MODE, privateMode)
                .putExtra(EXTRA_OWNER_UUID, ownerWebAppUuid)
                .putExtra(EXTRA_TRANSLATE_TARGET, translateTarget)
        }
    }
}
