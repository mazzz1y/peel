package wtf.mazy.peel.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import wtf.mazy.peel.R
import java.util.concurrent.atomic.AtomicInteger

class DownloadNotification(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val id: Int = nextId.getAndIncrement()

    init {
        ensureChannel(context)
    }

    fun buildProgress(
        fileName: String, webAppName: String?,
        current: Long, total: Long, cancelIntent: PendingIntent,
    ): Notification {
        val builder = progressBuilder(fileName, webAppName, cancelIntent)
        if (total > 0 && current > 0) {
            val percent = (current * 100 / total).toInt()
            builder.setProgress(100, percent, false).setContentText("$percent%")
        } else {
            builder.setProgress(0, 0, true)
                .setContentText(context.getString(R.string.download_in_progress))
        }
        return builder.build()
    }

    fun updateProgress(
        fileName: String, webAppName: String?,
        current: Long, total: Long, cancelIntent: PendingIntent,
    ) {
        manager.notify(id, buildProgress(fileName, webAppName, current, total, cancelIntent))
    }

    fun showSuccess(fileName: String, webAppName: String?, contentUri: Uri, mimeType: String?) {
        val mime = mimeType ?: "application/octet-stream"
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            id, baseBuilder(fileName, webAppName)
                .setContentText(context.getString(R.string.download_complete))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showError(fileName: String, webAppName: String?) {
        manager.notify(
            id, baseBuilder(fileName, webAppName)
                .setContentText(context.getString(R.string.download_failed))
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismiss() {
        manager.cancel(id)
    }

    private fun baseBuilder(fileName: String, webAppName: String?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24dp)
            .setContentTitle(fileName)
            .apply { webAppName?.let { setSubText(it) } }

    private fun progressBuilder(
        fileName: String, webAppName: String?, cancelIntent: PendingIntent,
    ): NotificationCompat.Builder =
        baseBuilder(fileName, webAppName)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.cancel), cancelIntent)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private val nextId = AtomicInteger(900_000)

        fun ensureChannel(context: Context) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.download_channel_description)
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
