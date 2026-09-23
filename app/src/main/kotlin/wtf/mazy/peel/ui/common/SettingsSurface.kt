package wtf.mazy.peel.ui.common

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import wtf.mazy.peel.R

object SettingsSurface {

    fun apply(view: View, position: GroupPosition) {
        val res = view.resources
        val outer = res.getDimension(R.dimen.settings_group_corner_outer)
        val inner = res.getDimension(R.dimen.settings_group_corner_inner)
        val top = if (position.isTop) outer else inner
        val bottom = if (position.isBottom) outer else inner
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top)
            .setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom)
            .setBottomRightCornerSize(bottom)
            .build()
        view.background = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainer)
            )
        }
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = res.getDimensionPixelSize(
                if (position.isBottom) R.dimen.settings_section_gap else R.dimen.settings_group_seam
            )
        }
    }

    // Re-applies only when the visible set changes, so the pre-draw hook never forces a relayout.
    fun bindGroup(group: ViewGroup) {
        var applied: List<View> = emptyList()
        fun refresh() {
            val visible = group.children.filter { it.isVisible }.toList()
            if (visible == applied) return
            applied = visible
            visible.forEachIndexed { index, row -> apply(row, GroupPosition.of(index, visible.size)) }
        }
        refresh()
        group.viewTreeObserver.addOnPreDrawListener {
            refresh()
            true
        }
    }
}
