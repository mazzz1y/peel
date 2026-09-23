package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.browser.ExternalLinkResult
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.shareText

class LinkRouterActivity : PeelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)

        val url =
            intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toString()
                ?: run {
                    finish()
                    return
                }

        lifecycleScope.launch {
            DataManager.instance.loadAppData()
            val apps = DataManager.instance.activeWebsites
            if (apps.isEmpty()) {
                openIncognito(url)
                return@launch
            }
            ExternalLinkMenu.show(
                activity = this@LinkRouterActivity,
                url = url,
                excludeUuid = null,
                peelApps = apps,
                includeLoadHere = false,
                includeOpenInSystem = false,
            ) { result ->
                when (result) {
                    // the launcher may open a picker parented here, so it says when to finish
                    is ExternalLinkResult.OpenInPeelApp -> result.launcher(::finish)
                    ExternalLinkResult.OpenIncognito -> openIncognito(url)
                    ExternalLinkResult.Share -> shareText(url).also { finish() }
                    ExternalLinkResult.CopyLink -> copyToClipboard(url).also { finish() }
                    ExternalLinkResult.LoadHere,
                    ExternalLinkResult.OpenInSystem,
                    ExternalLinkResult.Dismissed -> finish()
                }
            }
        }
    }

    private fun openIncognito(url: String) {
        BrowserLauncher.launchIncognito(this, url)
        finish()
    }
}
