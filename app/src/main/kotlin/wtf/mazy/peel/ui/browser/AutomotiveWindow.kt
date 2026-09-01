package wtf.mazy.peel.ui.browser

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import wtf.mazy.peel.util.isAutomotiveHost

/** Window/inset helpers for AAOS: shell UI stays in the system safe area; PWA can go immersive. */
object AutomotiveWindow {
    private val insetTypes: Int
        get() = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout()

    fun applyStandardWindow(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    fun installSafeAreaOnRoot(activity: Activity, root: View) {
        if (!activity.isAutomotiveHost()) return
        applyStandardWindow(activity.window)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(insetTypes)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val horizontal = resolveHorizontalInsets(v.context, sys.left, sys.right, useFallback = true)
            v.setPaddingRelative(
                horizontal.first,
                sys.top,
                horizontal.second,
                maxOf(sys.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun horizontalInsetPx(
        context: Context,
        sysLeft: Int,
        sysRight: Int,
        useFallback: Boolean = true,
    ): Pair<Int, Int> = resolveHorizontalInsets(context, sysLeft, sysRight, useFallback)

    private fun resolveHorizontalInsets(
        context: Context,
        sysLeft: Int,
        sysRight: Int,
        useFallback: Boolean,
    ): Pair<Int, Int> {
        if (sysLeft > 0 || sysRight > 0) return sysLeft to sysRight
        if (!useFallback || !context.isAutomotiveHost()) return 0 to 0
        return when (AutomotiveDisplayLayout.detect(context)) {
            AutomotiveDisplayLayout.Layout.ULTRAWIDE,
            AutomotiveDisplayLayout.Layout.HORIZONTAL,
            -> {
                val safe = AutomotiveSafeZoneInsets.toPx(context)
                safe.start to safe.end
            }
            AutomotiveDisplayLayout.Layout.PORTRAIT -> 0 to 0
        }
    }
}
