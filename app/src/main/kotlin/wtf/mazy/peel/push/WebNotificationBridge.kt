package wtf.mazy.peel.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.AppPrefs

object WebNotificationBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeNotifications =
        object : LinkedHashMap<String, WebNotification>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, WebNotification>,
            ): Boolean = size > MAX_ACTIVE_NOTIFICATIONS
        }

    fun attach(runtime: GeckoRuntime, context: Context) {
        val appContext = context.applicationContext
        runtime.setWebNotificationDelegate(object : WebNotificationDelegate {
            override fun onShowNotification(notification: WebNotification) {
                scope.launch {
                    DataManager.instance.awaitReady()
                    show(appContext, notification)
                }
            }

            override fun onCloseNotification(notification: WebNotification) {
                NotificationManagerCompat.from(appContext)
                    .cancel(notification.tag, NOTIFICATION_ID)
                activeNotifications.remove(notification.tag)
            }
        })
    }

    fun onNotificationOpened(context: Context, tag: String, fallback: WebNotification?) {
        val notification = activeNotifications.remove(tag) ?: fallback
        notification?.let {
            it.click()
            it.dismiss()
        }
        NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID)
    }

    fun onNotificationDismissed(tag: String, fallback: WebNotification?) {
        (activeNotifications.remove(tag) ?: fallback)?.dismiss()
    }

    fun removeChannel(context: Context, channelKey: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(channelKey)
    }

    private suspend fun show(context: Context, notification: WebNotification) {
        if (notification.privateBrowsing) return
        if (!AppPrefs.isPushEnabled(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val contextId = PushBridge.scopeContextId(notification.origin)
        val host = PushBridge.scopeHost(notification.origin) ?: return
        val originUrl = PushBridge.scopeOrigin(notification.origin)
        val target = resolveTarget(contextId, originUrl)
        val channelKey = contextId ?: "site-$host"
        val channelName = sandboxTitle(contextId, target) ?: host

        ensureChannel(context, channelKey, channelName)
        activeNotifications[notification.tag] = notification

        val largeIcon = target?.let { withContext(Dispatchers.IO) { it.resolveIcon() } }
        if (notification.tag !in activeNotifications) return
        val builder = NotificationCompat.Builder(context, channelKey)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title ?: channelName)
            .setContentText(notification.text)
            .setAutoCancel(true)
            .setSilent(notification.silent)
            .setContentIntent(
                clickIntent(context, notification.tag, target?.uuid, originUrl, notification)
            )
            .setDeleteIntent(dismissIntent(context, notification.tag, notification))
            .setLargeIcon(largeIcon)

        runCatching { manager.notify(notification.tag, NOTIFICATION_ID, builder.build()) }
        notification.show()
    }

    private fun resolveTarget(contextId: String?, originUrl: String): WebApp? {
        val candidates = DataManager.instance.getWebsites().filter { app ->
            !app.resolvePrivateMode() && app.resolveContextId() == contextId
        }
        return ExternalLinkMenu.bestPeelMatch(candidates, originUrl, excludeUuid = null)
    }

    private fun sandboxTitle(contextId: String?, target: WebApp?): String? {
        if (contextId == null) return null
        if (target?.uuid == contextId) return target.title
        return DataManager.instance.getGroup(contextId)?.title
            ?: DataManager.instance.getWebApp(contextId)?.title
            ?: target?.title
    }

    private fun clickIntent(
        context: Context,
        tag: String,
        webappUuid: String?,
        origin: String,
        notification: WebNotification,
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            NotificationClickLaunch.intent(context, tag, webappUuid, origin, notification),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(
        context: Context,
        tag: String,
        notification: WebNotification,
    ): PendingIntent {
        val intent = Intent(context, PushNotificationClickReceiver::class.java)
            .setData("peel-notification://dismiss/$tag".toUri())
            .putExtra(PushNotificationClickReceiver.EXTRA_TAG, tag)
            .putExtra(PushNotificationClickReceiver.EXTRA_NOTIFICATION, notification)
        return PendingIntent.getBroadcast(
            context,
            tag.hashCode() xor DISMISS_REQUEST_SALT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context, id: String, name: String) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(id)
        if (existing != null && existing.name == name) return
        manager.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private const val NOTIFICATION_ID = 800_000
    private const val DISMISS_REQUEST_SALT = 0x5A5A5A
    private const val MAX_ACTIVE_NOTIFICATIONS = 64
}
