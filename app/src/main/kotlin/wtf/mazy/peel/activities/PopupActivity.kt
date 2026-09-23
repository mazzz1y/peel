package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.PeelTranslationDelegate
import wtf.mazy.peel.browser.PopupLaunch
import wtf.mazy.peel.browser.SessionHandoff
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.extensions.SessionExtensionActions
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.NotificationUtils

class PopupActivity : SessionPageActivity() {

    private lateinit var snapshotSettings: WebAppSettings

    override val effectiveSettings: WebAppSettings
        get() = snapshotSettings

    override val showToolbar = false

    override val isContentInitiatedWindow = true

    override val webAppName: String
        get() = intent.getStringExtra(PopupLaunch.EXTRA_TITLE).orEmpty().ifEmpty { lastLoadedUrl }

    override val policyOrigin: String
        get() = intent.getStringExtra(PopupLaunch.EXTRA_POLICY_ORIGIN).orEmpty()

    override val externalLinkExcludeUuid: String?
        get() = ownerWebAppUuid

    override val sessionContextId: String?
        get() = intent.getStringExtra(PopupLaunch.EXTRA_CONTEXT_ID)
    override val sessionPrivateMode: Boolean
        get() = intent.getBooleanExtra(PopupLaunch.EXTRA_PRIVATE_MODE, false)

    override val ownerWebAppUuid: String?
        get() = intent.getStringExtra(PopupLaunch.EXTRA_OWNER_UUID)

    override fun onCreate(savedInstanceState: Bundle?) {
        snapshotSettings = intent.getStringExtra(PopupLaunch.EXTRA_SETTINGS)
            ?.let { runCatching { Json.decodeFromString<WebAppSettings>(it) }.getOrNull() }
            ?: DataManager.instance.defaultSettings.settings
        super.onCreate(savedInstanceState)
    }

    override fun onSessionHostReady() {
        val key = intent.getStringExtra(PopupLaunch.EXTRA_SESSION_KEY) ?: run { finish(); return }
        val popup = SessionHandoff.take(key)?.session ?: run { finish(); return }
        connectSession(popup)
        translationDelegate = PeelTranslationDelegate(this).also {
            it.presetManualTarget(intent.getStringExtra(PopupLaunch.EXTRA_TRANSLATE_TARGET))
            popup.translationsSessionDelegate = it
        }
        sessionExtensionActions.attach(popup)
        displaySession(popup)
        attachSystemBars()
        lifecycleScope.launch { setupThemeColorExtensionIfEnabled() }
        loadTranslationSupport()
    }

    override fun onDestroy() {
        intent.getStringExtra(PopupLaunch.EXTRA_SESSION_KEY)?.let { SessionHandoff.take(it)?.session?.close() }
        super.onDestroy()
    }

    override fun onLocationChanged(url: String) {
        super.onLocationChanged(url)
        translationDelegate?.onLocationChanged(url)
    }

    override fun onProcessKilled() = finish()

    override fun onContentCrashed() = finish()

    override fun onInitialNavigationDenied() = finish()

    override fun navigateHome() = launchOwnerApp(url = null)

    private fun launchOwnerApp(url: String?) {
        val ownerUuid = ownerWebAppUuid ?: return
        val webapp = DataManager.instance.getWebApp(ownerUuid) ?: run {
            NotificationUtils.showToast(this, getString(R.string.browser_launch_failed))
            return
        }
        BrowserLauncher.launch(webapp, this, url = url)
        finish()
    }

    override fun onSessionStarted() {
        SessionExtensionActions.setActive(sessionExtensionActions)
        showBrowserControls()
    }

    override fun onSessionStopped() {
        hideBrowserControls()
    }

    override val floatingControlsKey: String
        get() = ownerWebAppUuid ?: FLOATING_CONTROLS_KEY

    companion object {
        private const val FLOATING_CONTROLS_KEY = "popup"
    }
}
