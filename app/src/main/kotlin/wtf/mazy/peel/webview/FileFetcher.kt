package wtf.mazy.peel.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class FileFetcher(
    private val cacheDir: File,
    private val getWebView: () -> WebView?,
) {
    val bridge = BlobBridge()

    fun fetch(url: String, fileName: String?, onResult: (File?) -> Unit) {
        when {
            url.startsWith("blob:") -> fetchBlob(url, fileName, onResult)
            url.startsWith("data:") -> onResult(decodeDataUrl(url, fileName))
            else -> fetchHttp(url, fileName, onResult)
        }
    }

    private fun fetchBlob(url: String, fileName: String?, onResult: (File?) -> Unit) {
        val webView = getWebView() ?: run { onResult(null); return }
        bridge.arm(fileName, onResult)
        val safeUrl = org.json.JSONObject.quote(url)
        val js = """
            (function() {
                function read(b) {
                    if (!b || b.size === 0) { $BRIDGE_NAME.onBlobReady(''); return; }
                    if (b.size > $MAX_BLOB_BYTES) { $BRIDGE_NAME.onBlobReady('$ERROR_TOO_LARGE'); return; }
                    var rd = new FileReader();
                    rd.onloadend = function() { $BRIDGE_NAME.onBlobReady(rd.result || ''); };
                    rd.readAsDataURL(b);
                }
                var b = window['$PROP_GET'] && window['$PROP_GET']($safeUrl);
                if (b) { read(b); return; }
                fetch($safeUrl).then(function(r) {
                    if (!r.ok) throw new Error();
                    return r.blob();
                }).then(read).catch(function() { $BRIDGE_NAME.onBlobReady(''); });
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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
            } ?: MimeTypeMap.getFileExtensionFromUrl(url).ifEmpty { "bin" }
            val name = fileName ?: "file_${System.currentTimeMillis()}.$ext"
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
        val payload = dataUrl.substring(headerEnd + 1)
        val isBase64 = header.contains(";base64")
        val bytes = try {
            if (isBase64) Base64.decode(payload, Base64.DEFAULT)
            else Uri.decode(payload).toByteArray()
        } catch (_: Exception) {
            return null
        }
        val declaredMime = header.removePrefix("data:").replace(";base64", "")
        val name = resolveFileName(fileName, declaredMime, bytes)
        val file = File(prepareDir(), name)
        return try {
            file.writeBytes(bytes)
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

    @Suppress("unused")
    inner class BlobBridge {
        private var pending: Pair<String?, (File?) -> Unit>? = null
        var lastError: String? = null
            private set

        @Synchronized
        fun arm(fileName: String?, onResult: (File?) -> Unit) {
            lastError = null
            pending = Pair(fileName, onResult)
        }

        @Synchronized
        @JavascriptInterface
        fun onBlobReady(dataUrl: String) {
            val (fileName, onResult) = pending ?: return
            pending = null
            if (dataUrl.isEmpty() || dataUrl == ERROR_TOO_LARGE) {
                if (dataUrl == ERROR_TOO_LARGE) lastError = ERROR_TOO_LARGE
                Handler(Looper.getMainLooper()).post { onResult(null) }
                return
            }
            thread {
                val file = decodeDataUrl(dataUrl, fileName)
                Handler(Looper.getMainLooper()).post { onResult(file) }
            }
        }
    }

    companion object {
        const val BRIDGE_NAME = "_peelBlob"
        private const val CACHE_DIR = "shared_files"
        private const val STALE_MS = 5 * 60 * 1000L

        const val ERROR_TOO_LARGE = "ERR_TOO_LARGE"
        private const val MAX_BLOB_BYTES = 50 * 1024 * 1024
        private const val PROP = "_pb"
        private const val PROP_GET = "_pg"

        val BLOB_STORE_JS = """
            (function() {
                var p = '$PROP';
                if (window[p]) return;
                var store = {};
                var def = function(k, v) {
                    Object.defineProperty(window, k, {value:v, enumerable:false, configurable:false, writable:false});
                };
                def(p, true);
                var realCreate = URL.createObjectURL.bind(URL);
                var realRevoke = URL.revokeObjectURL.bind(URL);
                URL.createObjectURL = function(blob) {
                    var url = realCreate(blob);
                    if (blob instanceof Blob) store[url] = blob;
                    return url;
                };
                URL.revokeObjectURL = function(url) { realRevoke(url); };
                def('$PROP_GET', function(url) {
                    var b = store[url];
                    delete store[url];
                    return b || null;
                });
            })()
        """.trimIndent()

        private val WEAK_MIMES = setOf("text/plain", "application/octet-stream", "")

        private fun resolveFileName(hint: String?, declaredMime: String, bytes: ByteArray): String {
            val base = hint ?: "download"
            val dot = base.lastIndexOf('.')
            val ext = if (dot >= 0 && dot < base.lastIndex) base.substring(dot + 1) else ""
            if (ext.isNotEmpty() && ext != "txt" && ext != "bin") return base
            val mime = if (declaredMime in WEAK_MIMES) sniffMime(bytes) else declaredMime
            val newExt = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: ext.ifEmpty { "bin" }
            return "${base.substringBeforeLast('.')}.$newExt"
        }

        private fun sniffMime(bytes: ByteArray): String? {
            if (bytes.size < 4) return null
            return when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
                        && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()
                        && bytes[2] == 0x46.toByte() -> "image/gif"

                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()
                        && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
                        && bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
                        && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "image/webp"

                else -> null
            }
        }
    }
}
