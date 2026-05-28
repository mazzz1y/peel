package wtf.mazy.peel.ui.browser

import android.animation.ValueAnimator
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SystemBarController(
    private val window: Window,
    private val getThemeColor: () -> Int,
    private val setFullscreen: (Boolean) -> Unit,
) {
    private val insetsController by lazy {
        WindowInsetsControllerCompat(window, window.decorView)
    }
    private var statusBarScrim: View? = null
    private var navigationBarScrim: View? = null
    private var currentTopColor: Int? = null
    private var currentBottomColor: Int? = null
    private var topAnimator: ValueAnimator? = null
    private var bottomAnimator: ValueAnimator? = null
    var suppressNextAnimation = false

    fun attach(statusBarScrim: View?, navigationBarScrim: View?, applyDynamicColor: Boolean) {
        this.statusBarScrim = statusBarScrim
        this.navigationBarScrim = navigationBarScrim
        if (applyDynamicColor) {
            val themeColor = getThemeColor()
            window.decorView.setBackgroundColor(themeColor)
            applyTop(themeColor)
            applyBottom(themeColor)
        }
    }

    fun update(top: Int, bottom: Int, isOverlayVisible: Boolean, animationDurationMs: Long) {
        val instant = isOverlayVisible || suppressNextAnimation
        animateTop(top, animationDurationMs, instant)
        animateBottom(bottom, animationDurationMs, instant)
        if (instant) suppressNextAnimation = false
    }

    fun hide() {
        setFullscreen(true)
        statusBarScrim?.visibility = View.GONE
        navigationBarScrim?.visibility = View.GONE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.requestApplyInsets(window.decorView)
    }

    fun show(stayFullscreen: Boolean) {
        if (stayFullscreen) return
        setFullscreen(false)
        statusBarScrim?.visibility = View.VISIBLE
        navigationBarScrim?.visibility = View.VISIBLE
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(window.decorView)
    }

    fun release() {
        topAnimator?.cancel()
        topAnimator = null
        bottomAnimator?.cancel()
        bottomAnimator = null
    }

    fun resetForSwap() {
        release()
        suppressNextAnimation = true
    }

    private fun animateTop(color: Int, durationMs: Long, instant: Boolean) {
        topAnimator?.cancel()
        topAnimator = null
        window.decorView.setBackgroundColor(color)
        val fromColor = currentTopColor ?: getThemeColor()
        if (instant || fromColor == color) {
            applyTop(color)
            return
        }
        topAnimator = ValueAnimator.ofArgb(fromColor, color).apply {
            duration = durationMs
            addUpdateListener { applyTop(it.animatedValue as Int) }
            start()
        }
    }

    private fun animateBottom(color: Int, durationMs: Long, instant: Boolean) {
        bottomAnimator?.cancel()
        bottomAnimator = null
        val fromColor = currentBottomColor ?: getThemeColor()
        if (instant || fromColor == color) {
            applyBottom(color)
            return
        }
        bottomAnimator = ValueAnimator.ofArgb(fromColor, color).apply {
            duration = durationMs
            addUpdateListener { applyBottom(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyTop(color: Int) {
        currentTopColor = color
        statusBarScrim?.setBackgroundColor(color)
        insetsController.isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(color) > 0.5
    }

    private fun applyBottom(color: Int) {
        currentBottomColor = color
        navigationBarScrim?.setBackgroundColor(color)
        insetsController.isAppearanceLightNavigationBars =
            ColorUtils.calculateLuminance(color) > 0.5
    }
}
