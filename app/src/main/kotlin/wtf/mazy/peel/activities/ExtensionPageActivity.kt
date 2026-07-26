package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.PopupSessionHolder
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings

class ExtensionPageActivity : SessionPageActivity() {

    override val effectiveSettings: WebAppSettings
        get() = DataManager.instance.defaultSettings.settings

    private val adoptedSessionKey: String?
        get() = intent.getStringExtra(EXTRA_SESSION_KEY)

    private var adoptedSession: GeckoSession? = null

    override fun onSessionHostReady() {
        val key = adoptedSessionKey
        if (key != null) adoptSession(key) else openOptionsPage()
    }

    private fun adoptSession(key: String) {
        val session = PopupSessionHolder.take(key) ?: run { finish(); return }
        adoptedSession = session
        supportActionBar?.title =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.extensions)
        connectSession(session)
        displaySession(session)
    }

    private fun openOptionsPage() {
        val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: run { finish(); return }
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
        val session = adoptedSession
        if (session != null) {
            val callback = synchronized(onCloseCallbacks) { onCloseCallbacks.remove(session) }
            callback?.invoke()
        }
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
        geckoView?.releaseSession()
        geckoSession?.close()
        openSession(baseUrl)
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extension_id"
        private const val EXTRA_SESSION_KEY = "session_key"
        private const val EXTRA_TITLE = "title"

        private val onCloseCallbacks = mutableMapOf<GeckoSession, () -> Unit>()

        fun intentForExtension(context: Context, extensionId: String): Intent {
            return Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_EXTENSION_ID, extensionId)
        }

        fun intentForSession(context: Context, key: String, title: String): Intent {
            return Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_SESSION_KEY, key)
                .putExtra(EXTRA_TITLE, title)
        }

        fun setOnCloseCallback(session: GeckoSession, callback: () -> Unit) {
            synchronized(onCloseCallbacks) { onCloseCallbacks[session] = callback }
        }

        fun clearOnCloseCallback(session: GeckoSession) {
            synchronized(onCloseCallbacks) { onCloseCallbacks.remove(session) }
        }
    }
}
