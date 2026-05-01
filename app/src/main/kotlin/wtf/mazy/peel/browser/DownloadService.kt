package wtf.mazy.peel.browser

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeJobs = mutableMapOf<Int, Job>()
    private var currentForegroundId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> handleDownload(intent)
            ACTION_CANCEL -> handleCancel(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleDownload(intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "download"
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val contentLength = intent.getLongExtra(EXTRA_CONTENT_LENGTH, -1L)
        val webappName = intent.getStringExtra(EXTRA_WEBAPP_NAME)
        val body = pendingStreams.remove(requestId) ?: return

        val notification = DownloadNotification(this)
        val cancelPending = buildCancelPendingIntent(notification.id)
        val progressNotification = notification.buildProgress(fileName, webappName, cancelPending)

        ServiceCompat.startForeground(
            this, notification.id, progressNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0,
        )
        currentForegroundId = notification.id

        var lastNotifyTime = 0L
        val onProgress = { bytesCopied: Long ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifyTime >= 1000 && activeJobs.containsKey(notification.id)) {
                lastNotifyTime = now
                notification.updateProgress(
                    fileName,
                    webappName,
                    bytesCopied,
                    contentLength,
                    cancelPending
                )
            }
        }

        val job = scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    saveToDownloads(body, fileName, mimeType, onProgress)
                }
                if (uri != null) {
                    notification.showSuccess(fileName, webappName, uri, mimeType)
                    broadcastComplete(fileName, uri.toString(), mimeType)
                } else {
                    notification.showError(fileName, webappName)
                }
            } catch (_: CancellationException) {
                notification.dismiss()
            } finally {
                activeJobs.remove(notification.id)
                stopForegroundIfIdle()
            }
        }
        activeJobs[notification.id] = job
    }

    private fun handleCancel(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        activeJobs.remove(id)?.cancel()
        if (currentForegroundId == id) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            currentForegroundId = null
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        if (activeJobs.isEmpty()) stopSelf()
    }

    private fun stopForegroundIfIdle() {
        if (activeJobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            currentForegroundId = null
            stopSelf()
        }
    }

    private fun broadcastComplete(fileName: String, uriString: String, mimeType: String?) {
        sendBroadcast(
            Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_URI, uriString)
                putExtra(EXTRA_MIME_TYPE, mimeType)
            }
        )
    }

    private fun buildCancelPendingIntent(notificationId: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getService(
            this, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private suspend fun saveToDownloads(
        input: InputStream, fileName: String, mimeType: String?,
        onProgress: (Long) -> Unit,
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(input, fileName, mimeType, onProgress)
        } else {
            saveToLegacy(input, fileName, onProgress)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveToMediaStore(
        input: InputStream, fileName: String, mimeType: String?,
        onProgress: (Long) -> Unit,
    ): Uri? {
        val mime = resolveMime(fileName, mimeType)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { o -> input.use { it.copyToCancellable(o, onProgress) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun saveToLegacy(
        input: InputStream, fileName: String,
        onProgress: (Long) -> Unit,
    ): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = uniqueFile(dir, fileName)
        return try {
            target.outputStream().use { out -> input.use { it.copyToCancellable(out, onProgress) } }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        } catch (e: Exception) {
            target.delete()
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun InputStream.copyToCancellable(
        out: OutputStream, onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            onProgress(total)
        }
    }

    private fun resolveMime(fileName: String, mimeType: String?): String {
        return mimeType
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    companion object {
        const val ACTION_DOWNLOAD = "wtf.mazy.peel.DOWNLOAD"
        const val ACTION_CANCEL = "wtf.mazy.peel.CANCEL_DOWNLOAD"
        const val ACTION_DOWNLOAD_COMPLETE = "wtf.mazy.peel.DOWNLOAD_COMPLETE"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_CONTENT_LENGTH = "content_length"
        const val EXTRA_WEBAPP_NAME = "webapp_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_URI = "uri"

        private val pendingStreams = java.util.concurrent.ConcurrentHashMap<Int, InputStream>()
        private val nextRequestId = AtomicInteger()

        fun start(
            context: Context, fileName: String, mimeType: String?,
            contentLength: Long, webappName: String, body: InputStream,
        ) {
            val requestId = nextRequestId.getAndIncrement()
            pendingStreams[requestId] = body
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_CONTENT_LENGTH, contentLength)
                putExtra(EXTRA_WEBAPP_NAME, webappName)
            }
            context.startForegroundService(intent)
        }
    }
}
