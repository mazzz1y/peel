package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.util.WebViewLauncher

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrl() ?: run { finish(); return }
        DataManager.instance.loadAppData()
        val apps = DataManager.instance.activeWebsites
        if (apps.isEmpty()) { finish(); return }

        val sharedHost = sharedUrl.toUri().host ?: ""
        val sharedParts = sharedHost.lowercase().split('.').reversed()
        val sorted = apps.sortedWith(compareByDescending<WebApp> { domainAffinity(it, sharedParts) }.thenBy { it.title })

        showPickerDialog(sorted, sharedUrl)
    }

    private fun extractUrl(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return URL_PATTERN.find(text)?.value
    }

    companion object {
        private val URL_PATTERN = Regex("https?://[^\\s)>\\]\"]+")
    }

    private fun domainAffinity(webapp: WebApp, sharedParts: List<String>): Int {
        val appHost = webapp.baseUrl.toUri().host ?: return 0
        val appParts = appHost.lowercase().split('.').reversed()
        var match = 0
        for (i in 0 until minOf(sharedParts.size, appParts.size)) {
            if (sharedParts[i] == appParts[i]) match++ else break
        }
        return match
    }

    private fun showPickerDialog(apps: List<WebApp>, url: String) {
        val adapter = ListPickerAdapter(apps) { webapp, icon, name, detail ->
            name.text = webapp.title
            icon.setImageBitmap(webapp.resolveIcon())
            val groupName = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
            if (groupName != null) {
                detail.text = groupName
                detail.visibility = View.VISIBLE
            } else {
                detail.visibility = View.GONE
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_in_peel)
            .setAdapter(adapter) { _, position -> launchWebApp(apps[position], url) }
            .setOnCancelListener { finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun launchWebApp(webapp: WebApp, url: String) {
        val intent = WebViewLauncher.createWebViewIntent(webapp, this) ?: return
        intent.data = url.toUri()
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }
}
