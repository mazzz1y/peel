package wtf.mazy.peel.browser

import android.content.pm.ActivityInfo
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.OrientationController
import wtf.mazy.peel.util.ForegroundActivityTracker

/**
 * The delegate slot is runtime-wide while `requestedOrientation` is per-activity, so the target
 * is resolved on each callback instead of being bound at registration.
 */
object PeelOrientationDelegate : OrientationController.OrientationDelegate {

    // The default implementation returns null, which Gecko reports to content as "Not supported".
    override fun onOrientationLock(orientation: Int): GeckoResult<AllowOrDeny> {
        val host = ForegroundActivityTracker.current as? SessionHost
            ?: return GeckoResult.fromValue(AllowOrDeny.DENY)
        val applied = host.applyWebOrientation(orientation)
        return GeckoResult.fromValue(if (applied) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
    }

    override fun onOrientationUnlock() {
        val host = ForegroundActivityTracker.current as? SessionHost ?: return
        host.applyWebOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
}
