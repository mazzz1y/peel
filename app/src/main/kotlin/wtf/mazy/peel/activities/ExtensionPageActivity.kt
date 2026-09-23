package wtf.mazy.peel.activities

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.SessionHandoff
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.ui.extensions.ExtensionPageLaunch

class ExtensionPageActivity : SessionPageActivity() {

    override val effectiveSettings: EffectiveSettings
        get() = DataManager.globalEffectiveSettings

    override val tracksExtensionState: Boolean = false

    private val adoptedSessionKey: String?
        get() = intent.getStringExtra(ExtensionPageLaunch.EXTRA_SESSION_KEY)

    private var adopted: SessionHandoff.HandedSession? = null

    override fun onSessionHostReady() {
        val key = adoptedSessionKey
        if (key != null) adoptSession(key) else openOptionsPage()
    }

    private fun adoptSession(key: String) {
        val handed = SessionHandoff.take(key) ?: run { finish(); return }
        adopted = handed
        val session = handed.session
        supportActionBar?.title =
            intent.getStringExtra(ExtensionPageLaunch.EXTRA_TITLE) ?: getString(R.string.extensions)
        connectSession(session)
        displaySession(session)
    }

    private fun openOptionsPage() {
        val extensionId = intent.getStringExtra(ExtensionPageLaunch.EXTRA_EXTENSION_ID)
            ?: run { finish(); return }
        lifecycleScope.launch {
            val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionPageActivity)
            val ext = extensions.find { it.id == extensionId } ?: run { finish(); return@launch }
            val optionsUrl = ext.metaData.optionsPageUrl ?: run { finish(); return@launch }
            supportActionBar?.title = ext.metaData.name ?: ext.id
            openSession(optionsUrl)
        }
    }

    private fun openSession(url: String) {
        val session = createSession(effectiveSettings)
        baseUrl = url
        connectSession(session)
        session.open(GeckoRuntimeProvider.getRuntime(this))
        val restore = lastSessionState
        if (restore != null) session.restoreState(restore) else session.loadUri(url)
        displaySession(session)
    }

    override fun onDestroy() {
        adopted?.onClose?.invoke()
        super.onDestroy()
    }

    override fun onProcessKilled() = recoverOrFinish()

    override fun onContentCrashed() = recoverOrFinish()

    private fun recoverOrFinish() {
        if (adoptedSessionKey != null) {
            finish()
            return
        }
        if (isFinishing || isDestroyed) return
        closeGeckoSession()
        openSession(baseUrl)
    }
}
