package wtf.mazy.peel.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import org.mozilla.geckoview.WebNotification

class PushNotificationClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val fallback = IntentCompat.getParcelableExtra(
            intent, EXTRA_NOTIFICATION, WebNotification::class.java,
        )
        WebNotificationBridge.onNotificationDismissed(tag, fallback)
    }

    companion object {
        const val EXTRA_TAG = "notification_tag"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
