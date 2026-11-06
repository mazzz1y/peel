package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.BrowserLauncher

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

        lifecycleScope.launch {
            DataManager.reloadAll()
            if (DataManager.webApps.isEmpty()) {
                openIncognito(url)
                return@launch
            }
            ExternalLinkMenu.openInPeelPicker(
                activity = this@IncomingLinkActivity,
                url = url,
                excludeUuid = null,
                onIncognito = { openIncognito(url) },
                onDismiss = ::finish,
            )
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
