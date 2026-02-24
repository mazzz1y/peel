package wtf.mazy.peel.webview

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp

class WebViewNotificationManager(
    private val activity: AppCompatActivity,
    private val getWebapp: () -> WebApp,
    private val getWebappUuid: () -> String,
    private val getWebView: () -> WebView?,
    private val onReload: () -> Unit,
    private val onHome: () -> Unit,
) {
    private val idHash: Int
        get() = getWebappUuid().hashCode().and(0x7FFFFFFF)

    private val actionReload: String
        get() = "wtf.mazy.peel.RELOAD.${getWebappUuid()}"

    private val actionHome: String
        get() = "wtf.mazy.peel.HOME.${getWebappUuid()}"

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                when (intent.action) {
                    actionReload -> onReload()
                    actionHome -> onHome()
                }
            }
        }

    fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "WebView Controls", NotificationManager.IMPORTANCE_MIN)
                .apply {
                    description = "Controls for WebView navigation"
                    setShowBadge(false)
                    setSound(null, null)
                }
        activity
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun registerReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(actionReload)
                addAction(actionHome)
            }
        ContextCompat.registerReceiver(
            activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver() {
        try {
            activity.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun showNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }

        val currentUrl = getWebView()?.url ?: "Loading..."
        val shareChooser =
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                },
                null,
            )

        val shareIntent =
            PendingIntent.getActivity(
                activity,
                RC_SHARE + idHash,
                shareChooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val reloadIntent =
            PendingIntent.getBroadcast(
                activity,
                RC_RELOAD + idHash,
                Intent(actionReload).apply { setPackage(activity.packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val homeIntent =
            PendingIntent.getBroadcast(
                activity,
                RC_HOME + idHash,
                Intent(actionHome).apply { setPackage(activity.packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val webapp = getWebapp()
        val notificationIcon = webapp.resolveIcon()

        val notification =
            NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(notificationIcon)
                .setContentTitle(webapp.title)
                .setContentText(getWebView()?.url ?: "Loading...")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .addAction(R.drawable.ic_baseline_home_24, "Home", homeIntent)
                .addAction(R.drawable.ic_baseline_content_copy_24, "Share", shareIntent)
                .addAction(R.drawable.ic_baseline_refresh_24, "Reload", reloadIntent)
                .build()

        NotificationManagerCompat.from(activity).notify(NOTIFICATION_BASE_ID + idHash, notification)
        return true
    }

    fun hideNotification() {
        NotificationManagerCompat.from(activity).cancel(NOTIFICATION_BASE_ID + idHash)
    }

    companion object {
        private const val CHANNEL_ID = "webview_controls"
        private const val RC_SHARE = 1
        private const val RC_RELOAD = 2
        private const val RC_HOME = 3
        private const val NOTIFICATION_BASE_ID = 1001
    }
}
