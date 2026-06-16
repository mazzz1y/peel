package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.showOpenInPeelPicker
import wtf.mazy.peel.util.NotificationUtils

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

            showOpenInPeelPicker(apps, sharedUrl) {
                setOnCancelListener { finish() }
                setOnDismissListener { finish() }
            }
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
}
