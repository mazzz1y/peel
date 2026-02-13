package wtf.mazy.peel.shortcut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.CookieManager
import java.net.URL
import java.util.TreeMap
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import wtf.mazy.peel.shortcut.ShortcutIconUtils.getWidthFromIcon
import wtf.mazy.peel.util.Const

object WebAppIconFetcher {

    private const val TAG = "WebAppIconFetcher"

    data class FetchResult(
        val title: String? = null,
        val icon: Bitmap? = null,
    )

    fun fetch(url: String): FetchResult {
        val metadata = fetchMetadata(url)
        val title = metadata["title"]
        val iconUrl = metadata["iconUrl"]
        val icon = iconUrl?.let { loadBitmapFromUrl(it) }
        return FetchResult(title, icon)
    }

    private fun fetchMetadata(baseUrl: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        val foundIcons = TreeMap<Int, String>()

        try {
            val response = httpGet(baseUrl) ?: return result
            val doc = response.parse()

            doc.select("title").first()?.text()?.let { result["title"] = it }

            val manifestUrl = resolveManifestUrl(doc, baseUrl)

            if (!manifestUrl.isNullOrEmpty()) {
                parseManifest(manifestUrl, result, foundIcons)
            }

            if (foundIcons.isEmpty()) {
                findHtmlLinkIcons(doc, foundIcons)
            }

            if (foundIcons.isEmpty()) {
                findFallbackFavicons(baseUrl, foundIcons)
            }

            if (foundIcons.isNotEmpty()) {
                result["iconUrl"] = foundIcons.lastEntry()?.value
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch webapp data", e)
        }

        return result
    }

    private fun resolveManifestUrl(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        val manifestLink = doc.select("link[rel=manifest]").first()
        if (manifestLink != null) {
            val href = manifestLink.absUrl("href")
            if (href.isNotEmpty()) return href
        }

        val baseUrlObj = URL(baseUrl)
        val baseHost = "${baseUrlObj.protocol}://${baseUrlObj.host}"
        val manifestPaths = listOf("/manifest.json", "/manifest.webmanifest", "/site.webmanifest")
        for (path in manifestPaths) {
            val testUrl = "$baseHost$path"
            val testResponse = httpGet(testUrl, timeout = 2000, ignoreContentType = true)
            if (testResponse?.statusCode() == 200) return testUrl
        }
        return null
    }

    private fun parseManifest(
        manifestUrl: String,
        result: MutableMap<String, String?>,
        foundIcons: TreeMap<Int, String>,
    ) {
        try {
            val manifestResponse = httpGet(manifestUrl, ignoreContentType = true) ?: return
            val json = JSONObject(manifestResponse.body())

            try {
                result["title"] = json.getString("name")
            } catch (_: JSONException) {}

            val icons = json.optJSONArray("icons") ?: return
            for (i in 0 until icons.length()) {
                val iconObj = icons.optJSONObject(i) ?: continue
                val iconHref = iconObj.optString("src")
                if (iconHref.isEmpty()) continue
                if (iconHref.endsWith(".svg")) continue
                var width = getWidthFromIcon(iconObj.optString("sizes", ""))
                if (iconObj.optString("purpose", "").contains("maskable")) {
                    width += 20000
                }
                foundIcons[width] = URL(URL(manifestUrl), iconHref).toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse manifest", e)
        }
    }

    private fun findHtmlLinkIcons(doc: org.jsoup.nodes.Document, foundIcons: TreeMap<Int, String>) {
        val icons = doc.select("link[rel*=icon]")
        val supportedExtensions = listOf(".png", ".jpg", ".jpeg", ".ico", ".webp")

        for (icon in icons) {
            val iconHref = icon.absUrl("href")
            if (iconHref.isEmpty()) continue
            val lowerHref = iconHref.lowercase()
            if (supportedExtensions.none { lowerHref.endsWith(it) }) continue
            val rel = icon.attr("rel").lowercase()
            val sizes = icon.attr("sizes")
            var width = if (sizes.isNotEmpty()) getWidthFromIcon(sizes) else 1
            if (rel.contains("apple-touch-icon")) {
                width += 10000
            }
            foundIcons[width] = iconHref
        }
    }

    private fun findFallbackFavicons(baseUrl: String, foundIcons: TreeMap<Int, String>) {
        val baseUrlObj = URL(baseUrl)
        val baseHost = "${baseUrlObj.protocol}://${baseUrlObj.host}"
        val fallbackPaths =
            listOf(
                "/favicon.ico",
                "/favicon.png",
                "/assets/img/favicon.png",
                "/assets/favicon.png",
                "/static/favicon.png",
                "/images/favicon.png",
            )
        for (path in fallbackPaths) {
            val testBitmap = loadBitmapFromUrl("$baseHost$path")
            if (testBitmap != null) {
                foundIcons[0] = "$baseHost$path"
                break
            }
        }
    }

    private fun loadBitmapFromUrl(strUrl: String): Bitmap? {
        return try {
            val response = httpGet(strUrl, ignoreContentType = true) ?: return null
            val bytes = response.bodyAsBytes()
            val options =
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap == null || bitmap.width < Const.FAVICON_MIN_WIDTH) null else bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from URL: $strUrl", e)
            null
        }
    }

    private fun httpGet(
        url: String,
        timeout: Int = 5000,
        ignoreContentType: Boolean = false,
    ): Connection.Response? {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url)

        return try {
            val connection =
                Jsoup.connect(url)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(ignoreContentType)
                    .userAgent(Const.DESKTOP_USER_AGENT)
                    .followRedirects(true)
                    .timeout(timeout)

            if (cookies != null) {
                connection.header("Cookie", cookies)
            }

            val response = connection.execute()
            response.headers("Set-Cookie").forEach { cookie ->
                cookieManager.setCookie(url, cookie)
            }
            cookieManager.flush()
            response
        } catch (_: Exception) {
            null
        }
    }
}
