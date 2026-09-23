package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.ui.dialog.ExternalLinkResult
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.shareText

/**
 * Receives links from outside Peel, both as the default browser (`ACTION_VIEW`) and as a
 * share target (`ACTION_SEND`), and offers the user's web apps as destinations.
 * The manifest exposes it under the historical `LinkRouterActivity` and
 * `ShareReceiverActivity` component names.
 */
class IncomingLinkActivity : PeelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)

        val url = extractUrl() ?: run {
            finish()
            return
        }
        // the share sheet this came from already offers share and copy
        val shared = intent.action == Intent.ACTION_SEND

        lifecycleScope.launch {
            DataManager.reloadAll()
            val apps = DataManager.sortedWebApps
            if (apps.isEmpty()) {
                openIncognito(url)
                return@launch
            }
            ExternalLinkMenu.show(
                activity = this@IncomingLinkActivity,
                url = url,
                excludeUuid = null,
                peelApps = apps,
                includeLoadHere = false,
                includeOpenInSystem = false,
                includeShareAndCopy = !shared,
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

    private fun extractUrl(): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data?.toString()
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { URL_PATTERN.find(it)?.value }

        else -> null
    }

    private fun openIncognito(url: String) {
        BrowserLauncher.launchIncognito(this, url)
        finish()
    }

    private companion object {
        val URL_PATTERN = Regex("https?://[^\\s)>\\]\"]+")
    }
}
