package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.LinkChoice
import wtf.mazy.peel.model.LinkOrigin
import wtf.mazy.peel.model.LinkSubject
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.push.NotificationClickLaunch
import wtf.mazy.peel.ui.common.DataRoutingScreen
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.ui.linksheet.LinkSheet
import wtf.mazy.peel.ui.linksheet.openElsewhere
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.WebUrl

/**
 * Receives links from outside Peel, both as the default browser (`ACTION_VIEW`) and as a
 * share target (`ACTION_SEND`), and asks in the link sheet where to open them.
 * The manifest exposes it under the historical `LinkRouterActivity` and
 * `ShareReceiverActivity` component names.
 */
class IncomingLinkActivity : PeelActivity(), DataRoutingScreen {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)

        val link = extractUrl()?.let(Redirectors::unwrap) ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            DataManager.reloadAll()
            if (DataManager.webApps.isEmpty()) {
                BrowserLauncher.launchIncognito(this@IncomingLinkActivity, link.launchUrl)
                finish()
                return@launch
            }
            LinkSheet.show(
                activity = this@IncomingLinkActivity,
                subject = LinkSubject.Web(link),
                origin = LinkOrigin.Outside(notificationSandboxFilter()),
                onChoice = ::perform,
            )
        }
    }

    private fun perform(choice: LinkChoice) {
        when (choice) {
            is LinkChoice.Elsewhere -> openElsewhere(choice, onDone = ::finish)
            else -> finish()
        }
    }

    // The exported aliases take links from other apps, which have no say in the list.
    private fun notificationSandboxFilter(): (WebApp) -> Boolean {
        if (intent.component?.className != IncomingLinkActivity::class.java.name) return { true }
        val sandbox = NotificationClickLaunch.sandbox(intent) ?: return { true }
        return { DataManager.effectiveSandbox(it).holds(sandbox.contextId) }
    }

    private fun extractUrl(): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> WebUrl.accept(intent.data?.toString())
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(WebUrl::find)
        else -> null
    }
}
