package wtf.mazy.peel.browser

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import androidx.core.view.isGone
import org.mozilla.geckoview.GeckoSession

class PeelProgressDelegate(private val host: SessionHost) : GeckoSession.ProgressDelegate {

    private var animator: ObjectAnimator? = null

    override fun onPageStart(session: GeckoSession, url: String) {
        host.onPageStarted()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        hideProgress()
        if (success) {
            host.onPageFullyLoaded()
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (host.effectiveSettings.isShowProgressbar == true || host.currentlyReloading) {
            host.hostProgressBar?.let { bar ->
                if (bar.isGone && progress < 100) bar.visibility = View.VISIBLE
                animateProgress(bar, progress)
                if (progress == 100) hideProgress()
            }
        }
        if (progress == 100) {
            host.onPageFullyLoaded()
        }
    }

    private fun animateProgress(bar: ProgressBar, target: Int) {
        animator?.cancel()
        animator = ObjectAnimator.ofInt(bar, "progress", bar.progress, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun hideProgress() {
        animator?.cancel()
        animator = null
        host.hostProgressBar?.apply {
            visibility = View.GONE
            progress = 0
        }
        host.currentlyReloading = false
    }
}
