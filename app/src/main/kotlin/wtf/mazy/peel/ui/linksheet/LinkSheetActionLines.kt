package wtf.mazy.peel.ui.linksheet

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import wtf.mazy.peel.R

/**
 * The link sheet's groups of actions: side by side when they fit across the sheet, or would but
 * for less than half of the last button, the enclosing scroll view then scrolling the line; else
 * one line each. Each group is a sideways-scrolling view around a row of buttons; the sheet's
 * inset is the rows' own padding, so a line that scrolls carries its buttons to the edge, and side
 * by side only the outer rows keep it, leaving the button gap between groups.
 */
class LinkSheetActionLines @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val inset = resources.getDimensionPixelSize(R.dimen.list_card_margin_horizontal)
    private val buttonGap = resources.getDimensionPixelSize(R.dimen.link_sheet_action_gap)
    private val lineGap = resources.getDimensionPixelSize(R.dimen.link_sheet_action_line_gap)
    private var oneLine = false

    private val groups get() = children.filter { it.isVisible }.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val groups = groups
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val buttons = groups.map { group ->
            group.actionRow.measure(unbounded, unbounded)
            group.actionRow.measuredWidth - group.actionRow.paddingStart - group.actionRow.paddingEnd
        }
        val lineWidth = buttons.sum() + buttonGap * (groups.size - 1) + 2 * inset
        val lastButton = groups.lastOrNull()?.actionRow?.children?.lastOrNull { it.isVisible }?.measuredWidth ?: 0
        oneLine = groups.size > 1 && lineWidth - width <= lastButton / 2
        var height = 0
        groups.forEachIndexed { i, group ->
            val start = if (!oneLine || i == 0) inset else 0
            val end = if (!oneLine || i == groups.lastIndex) inset else 0
            group.actionRow.updatePaddingRelative(start = start, end = end)
            val groupWidth = if (oneLine) buttons[i] + start + end else width
            group.measure(MeasureSpec.makeMeasureSpec(groupWidth, MeasureSpec.EXACTLY), unbounded)
            height = when {
                oneLine -> maxOf(height, group.measuredHeight)
                else -> height + group.measuredHeight + if (i > 0) lineGap else 0
            }
        }
        setMeasuredDimension(if (oneLine) maxOf(lineWidth, width) else width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var along = 0
        var down = 0
        for (group in groups) {
            val left = if (rtl) width - along - group.measuredWidth else along
            group.layout(left, down, left + group.measuredWidth, down + group.measuredHeight)
            if (oneLine) along += group.measuredWidth + buttonGap else down += group.measuredHeight + lineGap
        }
    }
}

internal val View.actionRow: ViewGroup get() = (this as HorizontalScrollView).getChildAt(0) as ViewGroup
