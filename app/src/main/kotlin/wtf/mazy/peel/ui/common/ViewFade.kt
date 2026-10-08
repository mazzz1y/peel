package wtf.mazy.peel.ui.common

import android.view.View
import wtf.mazy.peel.R
import wtf.mazy.peel.util.Const

/**
 * Fades [this] to the given visibility, leaving it `GONE` when hidden so it stops taking part
 * in layout and in accessibility traversal. A fade arriving while another is running re-targets
 * it, so the view settles on the state the last call asked for, and [onSettled] runs when it
 * gets there.
 */
fun View.fadeVisibility(
    visible: Boolean,
    animated: Boolean = true,
    onSettled: () -> Unit = {},
) = fade(visible, animated, hiddenVisibility = View.GONE, onSettled = onSettled)

/**
 * As [fadeVisibility], but the hidden view keeps its space, so the views beside it do not move.
 * Still unreachable to accessibility services while hidden.
 */
fun View.fadeVisibilityInPlace(visible: Boolean, animated: Boolean = true) =
    fade(visible, animated, hiddenVisibility = View.INVISIBLE)

/**
 * The view's own visibility and alpha cannot say whether a fade is already heading where this
 * call wants it: `ViewPropertyAnimator` draws its first frame on the next frame rather than on
 * `start()`, so a fade reversed within that window still reads the state it started from. The
 * last requested target is therefore remembered on the view, and any running fade is cancelled
 * before a new one is considered.
 */
private fun View.fade(
    visible: Boolean,
    animated: Boolean,
    hiddenVisibility: Int,
    onSettled: () -> Unit = {},
) {
    animate().cancel()
    val target = if (visible) View.VISIBLE else hiddenVisibility
    val targetAlpha = if (visible) 1f else 0f
    val alreadyThere = getTag(R.id.view_fade_target) == visible &&
            visibility == target &&
            alpha == targetAlpha
    setTag(R.id.view_fade_target, visible)
    if (!animated || alreadyThere) {
        alpha = targetAlpha
        visibility = target
        onSettled()
        return
    }
    if (visible && visibility != View.VISIBLE) {
        alpha = 0f
        visibility = View.VISIBLE
    }
    animate()
        .alpha(targetAlpha)
        .setDuration(Const.ANIM_DURATION_MEDIUM)
        .withEndAction {
            visibility = target
            onSettled()
        }
        .start()
}
