package wtf.mazy.peel.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps toolbar screens clear of every system bar except the one behind the app bar.
 *
 * AppBarLayout draws behind the status bar and takes that inset itself, but nothing below it
 * handles the sides (the AAOS rail) or the bottom, and CoordinatorLayout and AppBarLayout each
 * own the inset listener slot on their view. So the content root pads those three edges and
 * hands the top down untouched.
 */
fun Activity.applyToolbarScreenInsets() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
        insets.inset(bars.left, 0, bars.right, bars.bottom)
    }
}
