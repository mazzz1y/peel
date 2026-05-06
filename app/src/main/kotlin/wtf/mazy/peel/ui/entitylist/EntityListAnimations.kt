package wtf.mazy.peel.ui.entitylist

import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import wtf.mazy.peel.R
import wtf.mazy.peel.util.Const

object EntityListAnimations {

    private val PENDING_SWAP_TAG = R.id.toolbar_pending_swap
    private val IN_FLIGHT_TAG = R.id.toolbar_in_flight

    /**
     * Fades the toolbar out, runs [swap], then fades it back in.
     *
     * Concurrent calls are coalesced: while a crossfade is in-flight, only the most recent
     * pending swap survives — earlier pending swaps are dropped. Tag state managed:
     * - [IN_FLIGHT_TAG] = `true` for the duration of the fade-out + fade-in cycle.
     * - [PENDING_SWAP_TAG] holds the latest swap requested while another fade is in-flight.
     *
     * Invariant: external callers must not invoke `toolbar.animate().cancel()` while a
     * crossfade is in-flight, otherwise [IN_FLIGHT_TAG] gets stuck `true` and future
     * crossfades are dropped.
     */
    fun crossfadeToolbar(toolbar: MaterialToolbar, swap: () -> Unit) {
        if (toolbar.getTag(IN_FLIGHT_TAG) == true) {
            toolbar.setTag(PENDING_SWAP_TAG, PendingSwap(swap))
            return
        }
        toolbar.setTag(IN_FLIGHT_TAG, true)
        toolbar.setTag(PENDING_SWAP_TAG, null)
        toolbar.animate()
            .alpha(0f)
            .setDuration(Const.ANIM_DURATION_FAST)
            .withEndAction {
                val latest = (toolbar.getTag(PENDING_SWAP_TAG) as? PendingSwap)?.run ?: swap
                toolbar.setTag(PENDING_SWAP_TAG, null)
                latest()
                toolbar.animate()
                    .alpha(1f)
                    .setDuration(Const.ANIM_DURATION_FAST)
                    .withEndAction { toolbar.setTag(IN_FLIGHT_TAG, false) }
                    .start()
            }
            .start()
    }

    private class PendingSwap(val run: () -> Unit)

    fun animateFabSwap(fab: FloatingActionButton, @DrawableRes iconRes: Int) {
        if (!fab.isOrWillBeShown) {
            fab.setImageResource(iconRes)
            fab.show()
            return
        }
        fab.hide(object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onHidden(fab: FloatingActionButton) {
                fab.setImageResource(iconRes)
                fab.show()
            }
        })
    }

    fun bindFabResizeOnRotation(activity: AppCompatActivity, fab: FloatingActionButton) {
        activity.findViewById<View>(android.R.id.content)
            .addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) fab.requestLayout()
            }
    }
}
