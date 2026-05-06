package wtf.mazy.peel.ui.entitylist

import android.content.res.ColorStateList
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.util.Const

object EntityRowAnimator {

    private val PENDING_SLIDE_TAG = R.id.entity_row_pending_slide

    fun applyModeState(host: EntityRowView, inSelectionMode: Boolean) {
        host.menuButton.animate().cancel()
        host.indicators.forEach { it.animate().cancel() }
        cancelPendingSlide(host.menuButton)

        if (inSelectionMode) {
            host.menuButton.alpha = 0f
            if (host.menuButton.width == 0) {
                schedulePendingSlide(host)
            } else {
                applyIndicatorSlide(host)
            }
        } else {
            host.menuButton.alpha = 1f
            host.indicators.forEach { it.translationX = 0f }
        }
    }

    fun animateModeTransition(host: EntityRowView, inSelectionMode: Boolean) {
        cancelPendingSlide(host.menuButton)
        if (inSelectionMode) {
            host.menuButton.animate().alpha(0f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            visibleIndicators(host).forEach {
                it.animate()
                    .translationX(indicatorSlide(host.menuButton, it))
                    .setDuration(Const.ANIM_DURATION_MEDIUM)
                    .start()
            }
        } else {
            host.menuButton.animate().alpha(1f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            host.indicators.forEach {
                it.animate().translationX(0f).setDuration(Const.ANIM_DURATION_MEDIUM).start()
            }
        }
    }

    fun animateIconSwap(
        host: EntityRowView,
        owner: IconOwner,
        selected: Boolean,
        checkIconColor: Int,
    ) {
        val icon = host.itemIcon
        icon.animate().cancel()
        icon.animate()
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(Const.ANIM_DURATION_FAST)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                applyIconState(host, owner, selected, checkIconColor)
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Const.ANIM_DURATION_FAST)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    fun applyIconState(
        host: EntityRowView,
        owner: IconOwner,
        selected: Boolean,
        checkIconColor: Int,
    ) {
        val icon = host.itemIcon
        if (selected) {
            icon.background = null
            icon.setImageResource(R.drawable.ic_check_24)
            icon.imageTintList = ColorStateList.valueOf(checkIconColor)
        } else {
            icon.imageTintList = null
            icon.background = null
            icon.setImageBitmap(owner.resolveIcon())
        }
    }

    private fun schedulePendingSlide(host: EntityRowView) {
        val listener = OneShotPreDrawListener.add(host.menuButton) {
            host.menuButton.setTag(PENDING_SLIDE_TAG, null)
            applyIndicatorSlide(host)
        }
        host.menuButton.setTag(PENDING_SLIDE_TAG, listener)
    }

    private fun cancelPendingSlide(menuButton: View) {
        val pending = menuButton.getTag(PENDING_SLIDE_TAG) as? OneShotPreDrawListener ?: return
        pending.removeListener()
        menuButton.setTag(PENDING_SLIDE_TAG, null)
    }

    private fun applyIndicatorSlide(host: EntityRowView) {
        visibleIndicators(host).forEach {
            it.translationX = indicatorSlide(host.menuButton, it)
        }
    }

    private fun visibleIndicators(host: EntityRowView): List<ImageView> =
        host.indicators.filter { it.isVisible }

    private fun indicatorSlide(menuButton: View, indicator: View): Float =
        (menuButton.width + indicator.width) / 2f
}
