package wtf.mazy.peel.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlin.math.roundToInt

fun Context.isTelevisionHost(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

fun Context.isAutomotiveHost(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

/**
 * Hosts where the launcher offers no task switching and webapp shortcuts open full-screen
 * with no route back to Peel itself: the browser must offer an explicit way home.
 */
fun Context.isSingleWindowHost(): Boolean = isTelevisionHost() || isAutomotiveHost()

/**
 * Head units are viewed at arm's length, so every dp has to cover more of the driver's field
 * of view. Scaling density once means one set of layouts stays correct everywhere: 48dp touch
 * targets become the 64dp the automotive guidelines ask for, and Material's own internals
 * (dialogs, switches, popups) follow without per-widget overrides.
 */
const val AUTOMOTIVE_DENSITY_SCALE = 4f / 3f

fun Context.withHostDensity(): Context {
    if (!isAutomotiveHost()) return this
    val configuration = Configuration(resources.configuration)
    val scaled = (configuration.densityDpi * AUTOMOTIVE_DENSITY_SCALE).roundToInt()
    if (scaled == configuration.densityDpi) return this
    configuration.densityDpi = scaled
    // The screen is the same size in pixels, so it now measures fewer dp across. Without this
    // the stale width keeps sizing width-derived resources — dialogs end up wider than the window.
    configuration.screenWidthDp = scaleToDensity(configuration.screenWidthDp)
    configuration.screenHeightDp = scaleToDensity(configuration.screenHeightDp)
    configuration.smallestScreenWidthDp = scaleToDensity(configuration.smallestScreenWidthDp)
    return createConfigurationContext(configuration)
}

/**
 * GeckoView sizes web content from the application context, which [withHostDensity] never
 * reaches, so the same factor has to be handed to the runtime for pages to match the chrome.
 */
fun Context.webContentDensityOverride(): Float? =
    if (isAutomotiveHost()) resources.displayMetrics.density * AUTOMOTIVE_DENSITY_SCALE else null

private fun scaleToDensity(dp: Int): Int =
    if (dp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) dp
    else (dp / AUTOMOTIVE_DENSITY_SCALE).roundToInt()
