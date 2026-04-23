package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.browser.BaseSessionHost
import wtf.mazy.peel.browser.DownloadHandler
import wtf.mazy.peel.browser.PeelContentDelegate
import wtf.mazy.peel.browser.PeelNavigationDelegate
import wtf.mazy.peel.browser.PeelProgressDelegate
import wtf.mazy.peel.browser.PeelPromptDelegate
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu

class ExtensionPageActivity : BaseSessionHost() {

    override val effectiveSettings: WebAppSettings
        get() = DataManager.instance.defaultSettings.settings

    override var baseUrl: String = ""
    override val webAppName: String
        get() = supportActionBar?.title?.toString().orEmpty()

    override val externalLinkExcludeUuid: String? = null
    override val externalLinkPeelApps: List<WebApp>
        get() = DataManager.instance.activeWebsites
    override val externalLinkIncludeLoadHere: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(themeBackgroundColor.toDrawable())
        setupSessionHostLayout(showToolbar = true)
        applyWindowFlags(effectiveSettings)
        applyColorScheme()

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar?.setNavigationOnClickListener { finish() }

        geckoView?.coverUntilFirstPaint(themeBackgroundColor)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            openSession(url)
        } else {
            val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: run { finish(); return }
            lifecycleScope.launch {
                val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionPageActivity)
                val ext =
                    extensions.find { it.id == extensionId } ?: run { finish(); return@launch }
                val optionsUrl = ext.metaData.optionsPageUrl ?: run { finish(); return@launch }
                supportActionBar?.title = ext.metaData.name ?: ext.id
                openSession(optionsUrl)
            }
        }
    }

    private fun openSession(url: String) {
        val runtime = GeckoRuntimeProvider.getRuntime(this)
        val session = createSession(effectiveSettings)
        baseUrl = url
        lastLoadedUrl = url
        navigationDelegate = PeelNavigationDelegate(this)
        downloadHandler = DownloadHandler(
            activity = this,
            getRuntime = { GeckoRuntimeProvider.getRuntime(this) },
            scope = lifecycleScope,
            webappName = webAppName,
        )
        session.navigationDelegate = navigationDelegate
        session.contentDelegate = PeelContentDelegate(
            host = this,
            onDownload = { response -> downloadHandler.onExternalResponse(response) },
        )
        session.progressDelegate = PeelProgressDelegate(this)
        session.promptDelegate = PeelPromptDelegate(this)
        session.open(runtime)
        session.loadUri(url)
        geckoView?.setSession(session)
        geckoSession = session
        setupPullToRefresh(effectiveSettings)
    }

    override fun onDestroy() {
        geckoView?.releaseSession()
        geckoView = null
        geckoSession?.close()
        geckoSession = null
        super.onDestroy()
    }

    override fun onLocationChanged(url: String) {
        lastLoadedUrl = url
    }

    override fun onPageStarted() = Unit
    override fun onFirstContentfulPaint() = Unit

    override fun hideSystemBars() = Unit
    override fun showSystemBars() = Unit

    override fun updateStatusBarColor(color: Int) = Unit

    override fun findPeelAppMatches(url: String): List<WebApp> {
        return ExternalLinkMenu.findPeelAppMatches(
            DataManager.instance.activeWebsites,
            url,
            excludeUuid = null,
        )
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extension_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun intentForExtension(context: Context, extensionId: String): Intent {
            return Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_EXTENSION_ID, extensionId)
        }

        fun intentForUrl(context: Context, url: String, title: String): Intent {
            return Intent(context, ExtensionPageActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
        }
    }
}
