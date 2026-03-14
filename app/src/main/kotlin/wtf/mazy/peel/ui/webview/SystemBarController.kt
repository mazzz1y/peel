package wtf.mazy.peel.ui.webview

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
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
    private var currentBarColor: Int? = null
    private var barColorAnimator: ValueAnimator? = null
    var suppressNextAnimation = false

    fun attach(rootView: View, applyDynamicColor: Boolean) {
        statusBarScrim = rootView.findViewById(R.id.statusBarScrim)
        navigationBarScrim = rootView.findViewById(R.id.navigationBarScrim)
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
            insets
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
        statusBarScrim?.visibility = View.GONE
        navigationBarScrim?.visibility = View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    fun show(isFullscreen: Boolean) {
        if (isFullscreen) return
        statusBarScrim?.visibility = View.VISIBLE
        navigationBarScrim?.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(true)
            window.insetsController?.apply {
                show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
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
