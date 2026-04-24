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
    private var statusBarScrim: View? = null
    private var navigationBarScrim: View? = null
    private var currentBarColor: Int? = null
    private var barColorAnimator: ValueAnimator? = null
    var suppressNextAnimation = false

    fun attach(statusBarScrim: View?, navigationBarScrim: View?, applyDynamicColor: Boolean) {
        this.statusBarScrim = statusBarScrim
        this.navigationBarScrim = navigationBarScrim
        if (applyDynamicColor) {
            applyColor(getThemeColor())
        }
    }

    fun update(color: Int, isOverlayVisible: Boolean, animationDurationMs: Long) {
        if (isOverlayVisible || suppressNextAnimation) {
            applyColor(color)
            suppressNextAnimation = false
            return
        }
        val fromColor = currentBarColor ?: getThemeColor()
        if (fromColor == color) {
            applyColor(color)
            return
        }
        barColorAnimator?.cancel()
        barColorAnimator = ValueAnimator.ofArgb(fromColor, color).apply {
            duration = animationDurationMs
            addUpdateListener { applyColor(it.animatedValue as Int) }
            start()
        }
    }

    fun hide() {
        setFullscreen(true)
        statusBarScrim?.visibility = View.GONE
        navigationBarScrim?.visibility = View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.requestApplyInsets(window.decorView)
    }

    fun show(stayFullscreen: Boolean) {
        if (stayFullscreen) return
        setFullscreen(false)
        statusBarScrim?.visibility = View.VISIBLE
        navigationBarScrim?.visibility = View.VISIBLE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(window.decorView)
    }

    fun release() {
        barColorAnimator?.cancel()
        barColorAnimator = null
    }

    fun resetForSwap() {
        barColorAnimator?.cancel()
        barColorAnimator = null
        suppressNextAnimation = true
    }

    private fun applyColor(color: Int) {
        currentBarColor = color
        statusBarScrim?.setBackgroundColor(color)
        navigationBarScrim?.setBackgroundColor(color)
        val isLight = ColorUtils.calculateLuminance(color) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }
}
