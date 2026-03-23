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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeckoRuntimeProvider {

    const val THEME_COLOR_APP = "themeColor"

    @Volatile
    private var runtime: GeckoRuntime? = null

    @Volatile
    private var themeColorExtension: WebExtension? = null

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

    private fun createRuntime(context: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.NONE)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .build()
            )
            .build()
        return GeckoRuntime.create(context, settings)
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

    private const val TAG = "PeelColor"
    private const val THEME_COLOR_URI = "resource://android/assets/extensions/theme-color/"
    private const val THEME_COLOR_ID = "theme-color@peel.mazy.wtf"
}
