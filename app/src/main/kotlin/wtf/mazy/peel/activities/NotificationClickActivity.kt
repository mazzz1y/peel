package wtf.mazy.peel.activities

import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebNotification
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.push.NotificationClickCoordinator
import wtf.mazy.peel.push.NotificationClickLaunch
import wtf.mazy.peel.push.WebNotificationBridge
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.util.BrowserLauncher

class NotificationClickActivity : PeelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra(NotificationClickLaunch.EXTRA_TAG)
        val webAppUuid = intent.getStringExtra(NotificationClickLaunch.EXTRA_WEBAPP_UUID)
        val origin = intent.getStringExtra(NotificationClickLaunch.EXTRA_ORIGIN)
        val sandbox = NotificationClickLaunch.sandbox(intent)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)
        val fallback = IntentCompat.getParcelableExtra(
            intent, NotificationClickLaunch.EXTRA_NOTIFICATION, WebNotification::class.java,
        )
        lifecycleScope.launch {
            DataManager.awaitReady()
            val webApp = webAppUuid?.let {
                DataManager.ensureWebAppLoaded(it)
                DataManager.webApp(it)
            }
            val pending = NotificationClickCoordinator.record(webApp?.uuid, sandbox)
            tag?.let {
                WebNotificationBridge.onNotificationOpened(
                    this@NotificationClickActivity,
                    it,
                    fallback
                )
            }
            when {
                webApp != null -> BrowserLauncher.launch(webApp, this@NotificationClickActivity)
                origin != null && !NotificationClickCoordinator.serviceWorkerWillNavigate(pending) ->
                    startActivity(
                        NotificationClickLaunch.listIntent(this@NotificationClickActivity, origin, sandbox)
                    )
            }
            finish()
        }
    }
}
