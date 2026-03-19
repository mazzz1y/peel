package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.ListPickerAdapter
import wtf.mazy.peel.util.NotificationUtils
import wtf.mazy.peel.util.WebViewLauncher
import wtf.mazy.peel.util.HostIdentity
import wtf.mazy.peel.util.shortLabel

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl =
            extractUrl()
                ?: run {
                    finish()
                    return
                }
        lifecycleScope.launch {
            DataManager.instance.loadAppData()
            val apps = DataManager.instance.activeWebsites
            if (apps.isEmpty()) {
                NotificationUtils.showToast(
                    this@ShareReceiverActivity,
                    getString(R.string.no_web_apps_available)
                )
                finish()
                return@launch
            }

            val sorted =
                apps.sortedWith(
                    compareByDescending<WebApp> {
                        HostIdentity.affinity(it.baseUrl, sharedUrl)
                    }.thenBy { it.title })

            showPickerDialog(sorted, sharedUrl)
        }
    }

    private fun extractUrl(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return URL_PATTERN.find(text)?.value
    }

    companion object {
        private val URL_PATTERN = Regex("https?://[^\\s)>\\]\"]+")
    }

    private fun showPickerDialog(apps: List<WebApp>, url: String) {
        val hasGroups = DataManager.instance.getGroups().isNotEmpty()
        val adapter =
            ListPickerAdapter(apps) { webapp, icon, name, detail ->
                name.text = webapp.title
                icon.setImageBitmap(webapp.resolveIcon())
                if (hasGroups) {
                    detail.text = webapp.groupUuid?.let { DataManager.instance.getGroup(it)?.title }
                        ?.let { shortLabel(it) } ?: getString(R.string.ungrouped)
                    detail.visibility = View.VISIBLE
                }
            }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_in_peel)
            .setAdapter(adapter) { _, position ->
                WebViewLauncher.startWebView(apps[position], this, url)
            }
            .setOnCancelListener { finish() }
            .setOnDismissListener { finish() }
            .show()
    }
}
