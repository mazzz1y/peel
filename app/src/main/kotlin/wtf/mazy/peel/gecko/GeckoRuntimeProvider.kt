package wtf.mazy.peel.gecko

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.BuildConfig
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebAppSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeckoRuntimeProvider {

    const val THEME_COLOR_APP = "themeColor"
    const val PAGE_INFO_APP = "pageInfo"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var themeColorExtension: WebExtension? = null

    @Volatile
    private var pageInfoExtension: WebExtension? = null

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { runtime = it }
        }
    }

    suspend fun ensureThemeColorExtension(context: Context): WebExtension? {
        themeColorExtension?.let { return it }
        return try {
            val ext = getRuntime(context).webExtensionController
                .ensureBuiltIn(THEME_COLOR_URI, THEME_COLOR_ID)
                .await()
            Log.d(TAG, "ensureThemeColorExtension: installed id=${ext.id}")
            themeColorExtension = ext
            ext
        } catch (e: Exception) {
            Log.d(TAG, "ensureThemeColorExtension failed: $e")
            null
        }
    }

    suspend fun ensurePageInfoExtension(context: Context): WebExtension? {
        pageInfoExtension?.let { return it }
        return try {
            val ext = getRuntime(context).webExtensionController
                .ensureBuiltIn(PAGE_INFO_URI, PAGE_INFO_ID)
                .await()
            pageInfoExtension = ext
            ext
        } catch (_: Exception) {
            null
        }
    }

    private fun createRuntime(context: Context): GeckoRuntime {
        val defaults = DataManager.instance.defaultSettings.settings
        val lna = defaults.isBlockLocalNetwork == true
        val antiTracking = when (defaults.isSafeBrowsing) {
            WebAppSettings.TRACKER_PROTECTION_STRICT -> ContentBlocking.AntiTracking.STRICT
            WebAppSettings.TRACKER_PROTECTION_DEFAULT -> ContentBlocking.AntiTracking.DEFAULT
            else -> ContentBlocking.AntiTracking.NONE
        }
        val builder = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .globalPrivacyControlEnabled(defaults.isGlobalPrivacyControl == true)
            .setLnaEnabled(lna)
            .setLnaBlocking(lna)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(antiTracking)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .build()
            )
        writeGeckoConfig(context, defaults)?.let { builder.configFilePath(it) }
        val rt = GeckoRuntime.create(context, builder.build())
        rt.settings.setFingerprintingProtection(defaults.isFingerprintingProtection == true)
        return rt
    }

    private fun writeGeckoConfig(
        context: Context,
        defaults: WebAppSettings,
    ): String? {
        val prefs = buildList {
            if (defaults.isBlockWebRtcIpLeak == true) {
                add("  media.peerconnection.ice.default_address_only: true")
                add("  media.peerconnection.ice.no_host: true")
            }
        }
        if (prefs.isEmpty()) return null
        val file = File(context.filesDir, GECKO_CONFIG_FILE)
        file.writeText("prefs:\n" + prefs.joinToString("\n") + "\n")
        return file.absolutePath
    }

    suspend fun <T> GeckoResult<T>.await(): T = suspendCancellableCoroutine { cont ->
        then(
            { value ->
                if (value != null) cont.resume(value)
                else cont.resumeWithException(NullPointerException("GeckoResult null"))
                GeckoResult<Void>()
            },
            { throwable ->
                cont.resumeWithException(throwable)
                GeckoResult<Void>()
            },
        )
    }

    private const val GECKO_CONFIG_FILE = "geckoview-config.yaml"
    private const val TAG = "PeelGecko"
    private const val THEME_COLOR_URI = "resource://android/assets/extensions/theme-color/"
    private const val THEME_COLOR_ID = "theme-color@peel.mazy.wtf"
    private const val PAGE_INFO_URI = "resource://android/assets/extensions/page-info/"
    private const val PAGE_INFO_ID = "peel@mazy.wtf"
}
