package wtf.mazy.peel.browser

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import androidx.core.animation.doOnEnd
import org.mozilla.geckoview.GeckoSession

class PeelProgressDelegate(
    private val host: SessionHost,
) : GeckoSession.ProgressDelegate {

    private var animator: ValueAnimator? = null
    private var currentUrl: String = ""
    private var displayProgress = 0f
    private var ceiling = BUCKET_SIZE.toFloat()
    private var ticking = false

    private val tickRunnable = Runnable { onTick() }

    override fun onPageStart(session: GeckoSession, url: String) {
        currentUrl = url
        if (isBlank(url)) return
        host.onPageStarted()
        if (host.effectiveSettings.isShowProgressbar != true && !host.currentlyReloading) return
        startLoad()
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (!ticking) return
        ceiling = (((progress / BUCKET_SIZE) + 1) * BUCKET_SIZE)
            .coerceAtMost(STREAM_CAP).toFloat()
        animateTo(progress.toFloat().coerceAtMost(STREAM_CAP.toFloat()))
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (isBlank(currentUrl)) return
        if (ticking) runTail()
        if (success) host.onPageFullyLoaded()
    }

    private fun startLoad() {
        val bar = host.hostProgressBar ?: return
        animator?.cancel()
        animator = null
        displayProgress = 0f
        ceiling = BUCKET_SIZE.toFloat()
        bar.max = PROGRESS_RESOLUTION
        bar.progress = 0
        bar.alpha = 1f
        ticking = true
        scheduleTick(bar)
    }

    private fun onTick() {
        if (!ticking) return
        val bar = host.hostProgressBar ?: return
        if (animator?.isRunning != true) {
            val remaining = ceiling - displayProgress
            if (remaining > 0f) {
                val step = (remaining * TRICKLE_K).coerceAtLeast(MIN_TRICKLE_STEP)
                displayProgress = (displayProgress + step).coerceAtMost(ceiling - TRICKLE_FLOOR)
                applyProgress(bar)
            }
        }
        scheduleTick(bar)
    }

    private fun scheduleTick(bar: ProgressBar) {
        bar.removeCallbacks(tickRunnable)
        bar.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private fun animateTo(target: Float) {
        val bar = host.hostProgressBar ?: return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayProgress, target).apply {
            duration = CATCHUP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayProgress = it.animatedValue as Float
                applyProgress(bar)
            }
            start()
        }
    }

    private fun runTail() {
        val bar = host.hostProgressBar ?: return
        stopTrickle(bar)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayProgress, 100f).apply {
            duration = TAIL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayProgress = it.animatedValue as Float
                applyProgress(bar)
            }
            doOnEnd {
                bar.animate()
                    .alpha(0f)
                    .setDuration(FADE_DURATION_MS)
                    .withEndAction {
                        bar.progress = 0
                        displayProgress = 0f
                        host.currentlyReloading = false
                    }
                    .start()
            }
            start()
        }
    }

    private fun applyProgress(bar: ProgressBar) {
        bar.progress = (displayProgress * PROGRESS_SCALE).toInt()
    }

    private fun stopTrickle(bar: ProgressBar) {
        ticking = false
        bar.removeCallbacks(tickRunnable)
    }

    private fun isBlank(url: String): Boolean = url == "about:blank"

    private companion object {
        const val BUCKET_SIZE = 10
        const val STREAM_CAP = 99
        const val PROGRESS_RESOLUTION = 10_000
        const val PROGRESS_SCALE = PROGRESS_RESOLUTION / 100f
        const val TICK_INTERVAL_MS = 16L
        const val TRICKLE_K = 0.015f
        const val MIN_TRICKLE_STEP = 0.04f
        const val TRICKLE_FLOOR = 0.5f
        const val CATCHUP_DURATION_MS = 250L
        const val TAIL_DURATION_MS = 150L
        const val FADE_DURATION_MS = 200L
    }
}
