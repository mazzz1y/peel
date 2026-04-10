package wtf.mazy.peel.activities

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

        val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: run { finish(); return }

        lifecycleScope.launch {
            val extensions = GeckoRuntimeProvider.listUserExtensions(this@ExtensionPageActivity)
            val ext = extensions.find { it.id == extensionId } ?: run { finish(); return@launch }

            val optionsUrl = ext.metaData.optionsPageUrl ?: run { finish(); return@launch }
            supportActionBar?.title = ext.metaData.name ?: ext.id

            val runtime = GeckoRuntimeProvider.getRuntime(this@ExtensionPageActivity)
            val session = GeckoSession()
            session.open(runtime)
            session.loadUri(optionsUrl)
            geckoView?.setSession(session)
            geckoSession = session
        }
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
    }
}
