package wtf.mazy.peel.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.net.toUri
import wtf.mazy.peel.model.LinkApp
import wtf.mazy.peel.model.LinkApps
import wtf.mazy.peel.model.LinkOpeners

// Anything but a web address is Android's to hand to whichever app takes it.
fun Context.openersOf(url: String): LinkOpeners {
    if (WebUrl.accept(url) == null) return LinkOpeners(emptyList(), null, asksSystem = true)
    val browsers = resolveViews(UNCLAIMED_ADDRESS).mapTo(HashSet()) { it.activityInfo.packageName }
    val handlers = resolveViews(url).map { it.app }
    val resolved = resolveView(UNCLAIMED_ADDRESS)?.app
    return LinkApps.openers(handlers, resolved, browsers, packageName)
}

fun Context.linkAppLabel(app: LinkApp): String =
    runCatching { packageManager.getActivityInfo(app.component, 0).loadLabel(packageManager).toString() }
        .getOrDefault(app.packageName)

fun Context.linkAppIcon(app: LinkApp): Drawable? =
    runCatching { packageManager.getActivityIcon(app.component) }.getOrNull()

fun Context.openInApp(url: String, app: LinkApp) {
    openInSystem(viewIntent(url).setComponent(app.component))
}

private val LinkApp.component: ComponentName get() = ComponentName(packageName, activityName)

private val ResolveInfo.app: LinkApp get() = LinkApp(activityInfo.packageName, activityInfo.name)

private fun viewIntent(url: String) =
    Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

private fun Context.resolveViews(url: String): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            viewIntent(url),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(viewIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
    }

private fun Context.resolveView(url: String): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(
            viewIntent(url),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(viewIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
    }

/** A web address no app claims, so only browsers open it. */
private const val UNCLAIMED_ADDRESS = "https://example.com/"
