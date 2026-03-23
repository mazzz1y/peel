package wtf.mazy.peel.ui.browser

import android.animation.ValueAnimator
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import wtf.mazy.peel.R

class SystemBarController(
    private val window: Window,
    private val getThemeColor: () -> Int,
) {
    private var statusBarScrim: View? = null
    private var navigationBarScrim: View? = null
    private var contentView: View? = null
    private var currentBarColor: Int? = null
    private var barColorAnimator: ValueAnimator? = null
    private var isFullscreen = false
    var suppressNextAnimation = false

    fun attach(rootView: View, applyDynamicColor: Boolean) {
        statusBarScrim = rootView.findViewById(R.id.statusBarScrim)
        navigationBarScrim = rootView.findViewById(R.id.navigationBarScrim)
        contentView = rootView.findViewById(R.id.browserContent)
        if (applyDynamicColor) {
            applyColor(getThemeColor())
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarScrim?.apply {
                layoutParams.height = systemInsets.top
                requestLayout()
            }
            navigationBarScrim?.apply {
                layoutParams.height = systemInsets.bottom
                requestLayout()
            }
            if (!isFullscreen) {
                contentView?.setPadding(0, systemInsets.top, 0, systemInsets.bottom)
            }
            WindowInsetsCompat.CONSUMED
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
        isFullscreen = true
        statusBarScrim?.visibility = View.GONE
        navigationBarScrim?.visibility = View.GONE
        contentView?.setPadding(0, 0, 0, 0)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun show(stayFullscreen: Boolean) {
        if (stayFullscreen) return
        isFullscreen = false
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
