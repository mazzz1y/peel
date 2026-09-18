package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.browser.ExternalLinkResult
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.BrowserLauncher

class ShareReceiverActivity : PeelActivity() {

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
                openIncognito(sharedUrl)
                return@launch
            }
            // the share sheet this came from already offers share and copy
            ExternalLinkMenu.show(
                activity = this@ShareReceiverActivity,
                url = sharedUrl,
                excludeUuid = null,
                peelApps = apps,
                includeLoadHere = false,
                includeOpenInSystem = false,
                includeShareAndCopy = false,
            ) { result ->
                when (result) {
                    is ExternalLinkResult.OpenInPeelApp -> result.launcher(::finish)
                    ExternalLinkResult.OpenIncognito -> openIncognito(sharedUrl)
                    ExternalLinkResult.LoadHere,
                    ExternalLinkResult.OpenInSystem,
                    ExternalLinkResult.Share,
                    ExternalLinkResult.CopyLink,
                    ExternalLinkResult.Dismissed -> finish()
                }
            }
        }
    }

    private fun openIncognito(url: String) {
        BrowserLauncher.launchIncognito(this, url)
        finish()
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
