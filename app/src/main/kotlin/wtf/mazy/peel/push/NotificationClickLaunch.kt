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
    private const val EXTRA_SANDBOX = "sandbox_context_id"
    private const val EXTRA_HAS_SANDBOX = "has_sandbox"

    /** The sandbox a notification came from; [contextId] is null for no sandbox. */
    data class Sandbox(val contextId: String?)

    fun intent(
        context: Context,
        tag: String,
        webAppUuid: String?,
        origin: String,
        sandbox: Sandbox,
        notification: WebNotification,
    ): Intent =
        Intent(context, ActivityRoutes.notificationClick)
            .setData("peel-notification://click/$tag".toUri())
            .putExtra(EXTRA_TAG, tag)
            .putExtra(EXTRA_WEBAPP_UUID, webAppUuid)
            .putExtra(EXTRA_ORIGIN, origin)
            .putExtra(EXTRA_NOTIFICATION, notification)
            .putSandbox(sandbox)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The web-app list for [url], limited to [sandbox]'s web apps when it is known. */
    fun listIntent(context: Context, url: String, sandbox: Sandbox?): Intent =
        Intent(context, ActivityRoutes.linkRouter)
            .setAction(Intent.ACTION_VIEW)
            .setData(url.toUri())
            .apply { if (sandbox != null) putSandbox(sandbox) }

    fun sandbox(intent: Intent): Sandbox? =
        if (intent.getBooleanExtra(EXTRA_HAS_SANDBOX, false)) {
            Sandbox(intent.getStringExtra(EXTRA_SANDBOX))
        } else null

    private fun Intent.putSandbox(sandbox: Sandbox): Intent =
        putExtra(EXTRA_HAS_SANDBOX, true).putExtra(EXTRA_SANDBOX, sandbox.contextId)
}
