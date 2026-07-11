package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings

class ExtensionPageActivity : SessionPageActivity() {

    override val effectiveSettings: WebAppSettings
        get() = DataManager.instance.defaultSettings.settings

    override fun onSessionHostReady() {
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
        lastLoadedUrl = url
        connectSession(session)
        session.open(GeckoRuntimeProvider.getRuntime(this))
        val restore = lastSessionState
        if (restore != null) session.restoreState(restore) else session.loadUri(url)
        displaySession(session)
    }

    override fun onProcessKilled() = recoverSession()

    override fun onContentCrashed() = recoverSession()

    private fun recoverSession() {
        if (isFinishing || isDestroyed) return
        geckoView?.releaseSession()
        geckoSession?.close()
        openSession(baseUrl)
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extension_id"

        fun intentForExtension(context: Context, extensionId: String): Intent {
            return Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_EXTENSION_ID, extensionId)
        }
    }
}
