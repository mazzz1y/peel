package wtf.mazy.peel.push

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebNotification
import wtf.mazy.peel.activities.LinkRouterActivity
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.BrowserLauncher

class NotificationClickActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra(EXTRA_TAG)
        val webappUuid = intent.getStringExtra(EXTRA_WEBAPP_UUID)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        GeckoRuntimeProvider.initAsync(this, warmUp = false)
        val fallback = IntentCompat.getParcelableExtra(
            intent, EXTRA_NOTIFICATION, WebNotification::class.java,
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
                        Intent(this@NotificationClickActivity, LinkRouterActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(origin.toUri())
                    )
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_TAG = "notification_tag"
        const val EXTRA_WEBAPP_UUID = "webapp_uuid"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
