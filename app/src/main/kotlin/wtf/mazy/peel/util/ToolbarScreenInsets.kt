package wtf.mazy.peel.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding

private val screenBars =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/**
 * Keeps toolbar screens clear of the side bars (the AAOS rail, display cutouts).
 *
 * AppBarLayout draws behind the status bar and takes that inset itself, and the scrolling
 * content owns the bottom via [applyBottomScreenInsets]. Nothing below the app bar handles the
 * sides, and CoordinatorLayout and AppBarLayout each own the inset listener slot on their own
 * view, so the content root pads left and right and hands the rest down untouched.
 */
fun Activity.applyToolbarScreenInsets() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(screenBars)
        view.updatePadding(left = bars.left, right = bars.right)
        insets.inset(bars.left, 0, bars.right, 0)
    }
}

/**
 * Lets scrolling content run edge-to-edge under the navigation bar and keyboard while keeping
 * its last row reachable: the view's own bottom padding grows by whichever of the two is taller.
 * Pair with `clipToPadding="false"`.
 */
fun View.applyBottomScreenInsets() {
    val basePadding = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(screenBars).bottom
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        view.updatePadding(bottom = basePadding + maxOf(bars, ime))
        insets
    }
    doOnAttach { it.requestApplyInsets() }
}
