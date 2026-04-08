package wtf.mazy.peel.ui.extensions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AmoExtensionsRepository {

    data class AmoExtension(
        val guid: String,
        val name: String,
        val iconUrl: String,
        val downloadUrl: String,
        val permissions: List<String>,
    )

    private const val TAG = "AmoExtensionsRepo"
    private const val AMO_URL =
        "https://addons.mozilla.org/api/v5/addons/search/?app=android&type=extension&promoted=recommended&page_size=50&sort=users"
    private const val ICON_PREFETCH_TIMEOUT_MS = 10_000L

    suspend fun fetchRecommended(context: Context): List<AmoExtension> =
        withContext(Dispatchers.IO) {
            val json = try {
                val conn = URL(AMO_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/json")
                conn.connect()
                if (conn.responseCode != 200) {
                    Log.w(TAG, "AMO fetch returned HTTP ${conn.responseCode}")
                    return@withContext emptyList<AmoExtension>()
                }
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                Log.w(TAG, "AMO fetch failed", e)
                return@withContext emptyList<AmoExtension>()
            }
            val list = parseAmoResponse(json)
            prefetchIcons(context, list)
            list
        }

    private suspend fun prefetchIcons(context: Context, list: List<AmoExtension>) {
        val targets = list.filter { it.iconUrl.isNotBlank() }
        if (targets.isEmpty()) return
        withTimeoutOrNull(ICON_PREFETCH_TIMEOUT_MS) {
            coroutineScope {
                targets.forEach { ext ->
                    launch {
                        ExtensionIconCache.downloadAndCache(context, ext.guid, ext.iconUrl)
                    }
                }
            }
        }
    }

    private fun parseAmoResponse(json: String): List<AmoExtension> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<AmoExtension>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val guid = obj.optString("guid", "").takeIf { it.isNotBlank() } ?: continue
            val nameObj = obj.optJSONObject("name")
            val name = nameObj?.optString("en-US")?.takeIf { it.isNotBlank() }
                ?: nameObj?.keys()?.asSequence()?.firstOrNull()
                    ?.let { nameObj.optString(it) }?.takeIf { it.isNotBlank() }
                ?: continue
            val iconUrl = obj.optString("icon_url", "")
            val currentVersion = obj.optJSONObject("current_version")
            val fileObj = currentVersion?.optJSONObject("file")
            val downloadUrl = fileObj?.optString("url", "")?.takeIf { it.isNotBlank() } ?: continue
            val permissions = fileObj.optJSONArray("permissions")
                ?.let { array ->
                    buildList {
                        for (j in 0 until array.length()) {
                            val perm = array.getString(j)
                            if (!perm.startsWith("http") && perm != "<all_urls>") {
                                add(perm)
                            }
                        }
                    }
                } ?: emptyList()
            list.add(AmoExtension(guid, name, iconUrl, downloadUrl, permissions))
        }
        return list
    }
}
