package wtf.mazy.peel.gecko

import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import wtf.mazy.peel.BuildConfig

object GeckoRuntimeProvider {

    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getRuntime(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: createRuntime(context.applicationContext).also { runtime = it }
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
}
