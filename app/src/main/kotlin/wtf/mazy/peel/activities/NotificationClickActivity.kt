package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebNotification
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.push.NotificationClickCoordinator
import wtf.mazy.peel.push.NotificationClickLaunch
import wtf.mazy.peel.push.WebNotificationBridge
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.util.ActivityRoutes
import wtf.mazy.peel.util.BrowserLauncher

class NotificationClickActivity : PeelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra(NotificationClickLaunch.EXTRA_TAG)
        val webappUuid = intent.getStringExtra(NotificationClickLaunch.EXTRA_WEBAPP_UUID)
        val origin = intent.getStringExtra(NotificationClickLaunch.EXTRA_ORIGIN)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)
        val fallback = IntentCompat.getParcelableExtra(
            intent, NotificationClickLaunch.EXTRA_NOTIFICATION, WebNotification::class.java,
        )
        lifecycleScope.launch {
            DataManager.instance.awaitReady()
            val webapp = webappUuid?.let {
                DataManager.instance.ensureWebAppLoaded(it)
                DataManager.instance.getWebApp(it)
            }
            val pending = NotificationClickCoordinator.record(webapp?.uuid)
            tag?.let {
                WebNotificationBridge.onNotificationOpened(
                    this@NotificationClickActivity,
                    it,
                    fallback
                )
            }
            when {
                webapp != null -> BrowserLauncher.launch(webapp, this@NotificationClickActivity)
                origin != null && !NotificationClickCoordinator.serviceWorkerWillNavigate(pending) ->
                    startActivity(
                        Intent(this@NotificationClickActivity, ActivityRoutes.linkRouter)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(origin.toUri())
                    )
            }
            finish()
        }
    }
}
