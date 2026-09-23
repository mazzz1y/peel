package wtf.mazy.peel.push

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.mozilla.geckoview.WebNotification
import wtf.mazy.peel.util.ActivityRoutes

object NotificationClickLaunch {
    const val EXTRA_TAG = "notification_tag"
    const val EXTRA_WEBAPP_UUID = "webapp_uuid"
    const val EXTRA_ORIGIN = "origin"
    const val EXTRA_NOTIFICATION = "notification"

    fun intent(
        context: Context,
        tag: String,
        webappUuid: String?,
        origin: String,
        notification: WebNotification,
    ): Intent =
        Intent(context, ActivityRoutes.notificationClick)
            .setData("peel-notification://click/$tag".toUri())
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_WEBAPP_UUID, webappUuid)
            .putExtra(EXTRA_ORIGIN, origin)
            .putExtra(EXTRA_NOTIFICATION, notification)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
