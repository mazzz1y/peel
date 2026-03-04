package wtf.mazy.peel.webview

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ImageCache(
    private val cacheDir: File,
    private val getWebView: () -> WebView?,
) {

    fun fetch(url: String, fileName: String?, onResult: (File?) -> Unit) {
        when {
            url.startsWith("blob:") -> fetchBlob(url, fileName, onResult)
            url.startsWith("data:") -> onResult(decodeDataUrl(url, fileName))
            else -> fetchHttp(url, fileName, onResult)
        }
    }

    private fun fetchBlob(url: String, fileName: String?, onResult: (File?) -> Unit) {
        val webView = getWebView() ?: run { onResult(null); return }
        val escapedUrl = url.replace("'", "\\'")
        val js = """
            (async function() {
                try {
                    var r = await fetch('$escapedUrl');
                    var b = await r.blob();
                    return await new Promise(function(ok) {
                        var rd = new FileReader();
                        rd.onloadend = function() { ok(rd.result); };
                        rd.readAsDataURL(b);
                    });
                } catch(e) { return ''; }
            })()
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val dataUrl = result?.trim('"')?.replace("\\n", "")?.replace("\\r", "") ?: ""
            thread {
                val file = decodeDataUrl(dataUrl, fileName)
                Handler(Looper.getMainLooper()).post { onResult(file) }
            }
        }
    }

    private fun fetchHttp(url: String, fileName: String?, onResult: (File?) -> Unit) {
        val cookie = CookieManager.getInstance().getCookie(url)
        val userAgent = getWebView()?.settings?.userAgentString ?: ""
        thread {
            val file = downloadToCache(url, fileName, cookie, userAgent)
            Handler(Looper.getMainLooper()).post { onResult(file) }
        }
    }

    private fun downloadToCache(
        url: String,
        fileName: String?,
        cookie: String?,
        userAgent: String,
    ): File? {
        val dir = prepareDir()
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connect()
            val contentType = conn.contentType?.substringBefore(";")?.trim()
            val ext = contentType?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: MimeTypeMap.getFileExtensionFromUrl(url).ifEmpty { "jpg" }
            val name = fileName ?: "image_${System.currentTimeMillis()}.$ext"
            val file = File(dir, name)
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeDataUrl(dataUrl: String, fileName: String?): File? {
        if (!dataUrl.startsWith("data:")) return null
        val headerEnd = dataUrl.indexOf(",")
        if (headerEnd < 0) return null
        val header = dataUrl.take(headerEnd)
        val base64Data = dataUrl.substring(headerEnd + 1)
        val mime = header.removePrefix("data:").removeSuffix(";base64")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val name = fileName ?: "image_${System.currentTimeMillis()}.$ext"
        val file = File(prepareDir(), name)
        return try {
            file.writeBytes(Base64.decode(base64Data, Base64.DEFAULT))
            file
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun prepareDir() = File(cacheDir, CACHE_DIR).apply {
        mkdirs()
        val staleThreshold = System.currentTimeMillis() - STALE_MS
        listFiles()?.forEach { if (it.lastModified() < staleThreshold) it.delete() }
    }

    companion object {
        private const val CACHE_DIR = "shared_images"
        private const val STALE_MS = 5 * 60 * 1000L
    }
}
