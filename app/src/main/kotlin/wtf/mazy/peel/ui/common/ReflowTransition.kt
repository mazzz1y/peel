package wtf.mazy.peel.ui.common

import android.animation.LayoutTransition
import android.view.ViewGroup
import wtf.mazy.peel.util.Const

/**
 * Animates a group's reflow when a child joins or leaves it, so the children that stay slide
 * and stretch into their new places instead of jumping there in one frame.
 *
 * Only the two "change" types are kept — they animate the staying children's bounds when a
 * sibling comes or goes, which is the whole point here. The appear and disappear types are
 * switched off: the children run their own fades, and a transition-owned alpha animation starts
 * by writing its first frame synchronously, snapping a half-faded child back to opaque. Their
 * default start delays go with them, so the group reflows with the fade rather than after it.
 */
fun ViewGroup.animateReflow() {
    layoutTransition = LayoutTransition().apply {
        setAnimateParentHierarchy(false)
        disableTransitionType(LayoutTransition.APPEARING)
        disableTransitionType(LayoutTransition.DISAPPEARING)
        setDuration(Const.ANIM_DURATION_MEDIUM)
        setStartDelay(LayoutTransition.CHANGE_APPEARING, 0)
        setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0)
    }
}
