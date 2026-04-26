package wtf.mazy.peel.browser

import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import org.mozilla.geckoview.GeckoSession

class PeelProgressDelegate(
    private val host: SessionHost,
    private val initialProgress: Int,
) : GeckoSession.ProgressDelegate {

    private var animator: ObjectAnimator? = null
    private var currentUrl: String = ""

    override fun onPageStart(session: GeckoSession, url: String) {
        currentUrl = url
        if (isBlank(url)) return
        host.onPageStarted()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (isBlank(currentUrl)) return
        hideProgress()
        if (success) {
            host.onPageFullyLoaded()
        }
    }

    private fun isBlank(url: String): Boolean = url == "about:blank"

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (host.effectiveSettings.isShowProgressbar == true || host.currentlyReloading) {
            host.hostProgressBar?.let { bar ->
                if (bar.alpha < 1f) bar.alpha = 1f
                animateProgress(bar, mapToVisibleRange(progress))
            }
        }
    }

    private fun mapToVisibleRange(progress: Int): Int =
        initialProgress + progress * (100 - initialProgress) / 100

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
            alpha = 0f
            progress = initialProgress
        }
        host.currentlyReloading = false
    }
}
