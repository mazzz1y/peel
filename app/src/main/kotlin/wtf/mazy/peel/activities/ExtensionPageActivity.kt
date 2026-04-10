package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.GeckoRuntimeProvider

class ExtensionPageActivity : AppCompatActivity() {

    private var geckoSession: GeckoSession? = null
    private var geckoView: GeckoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_page)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        geckoView = findViewById(R.id.gecko_view)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            openSession(url)
        } else {
            val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: run { finish(); return }
            lifecycleScope.launch {
                val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionPageActivity)
                val ext = extensions.find { it.id == extensionId } ?: run { finish(); return@launch }
                val optionsUrl = ext.metaData.optionsPageUrl ?: run { finish(); return@launch }
                supportActionBar?.title = ext.metaData.name ?: ext.id
                openSession(optionsUrl)
            }
        }
    }

    private fun openSession(url: String) {
        val runtime = GeckoRuntimeProvider.getRuntime(this)
        val session = GeckoSession()
        session.open(runtime)
        session.loadUri(url)
        geckoView?.setSession(session)
        geckoSession = session
    }

    override fun onDestroy() {
        geckoView?.releaseSession()
        geckoView = null
        geckoSession?.close()
        geckoSession = null
        super.onDestroy()
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
