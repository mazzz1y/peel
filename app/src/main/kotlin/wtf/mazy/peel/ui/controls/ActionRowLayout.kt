package wtf.mazy.peel.ui.controls

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import wtf.mazy.peel.R

class ActionRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** An action slot and the divider drawn after it, which never leaves the slot's row. */
    class Item(val slot: View, val divider: View?)

    private val minSlotWidth = resources.getDimensionPixelSize(R.dimen.bar_controls_slot_width)
    private val rowHeight = resources.getDimensionPixelSize(R.dimen.bar_controls_min_height)
    private val gap = resources.getDimensionPixelSize(R.dimen.bar_controls_button_gap)

    /** Widen slots so the fullest row spans the whole width, as a bottom panel does. */
    var stretchSlots = false
        set(value) {
            field = value
            requestLayout()
        }

    private var items: List<Item> = emptyList()
    private var rows: List<List<Item>> = emptyList()
    private var slotWidth = minSlotWidth

    fun setItems(items: List<Item>) {
        removeAllViews()
        this.items = items
        items.forEach { item ->
            addView(item.slot)
            item.divider?.let(::addView)
        }
    }

    private fun dividerSpan(item: Item): Int = item.divider?.let { it.measuredWidth + gap } ?: 0

    private fun rowWidth(row: List<Item>, slot: Int = slotWidth): Int =
        slot * row.size +
                gap * (row.size - 1).coerceAtLeast(0) +
                row.dropLast(1).sumOf(::dividerSpan)

    private fun split(perRow: Int): List<List<Item>> {
        var start = 0
        return ActionRows.split(items.size, perRow).map { count ->
            items.subList(start, start + count).also { start += count }
        }
    }

    private fun rowsFitting(available: Int): List<List<Item>> {
        var perRow = items.size
        var rows = split(perRow)
        while (perRow > 1 && rows.any { rowWidth(it, minSlotWidth) > available }) {
            perRow--
            rows = split(perRow)
        }
        return rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        items.forEach { item ->
            item.divider?.let {
                it.measure(
                    MeasureSpec.makeMeasureSpec(it.layoutParams.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(it.layoutParams.height, MeasureSpec.EXACTLY),
                )
            }
        }

        val padding = paddingLeft + paddingRight
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) MeasureSpec.getSize(widthMeasureSpec) - padding else Int.MAX_VALUE
        rows = rowsFitting(available)

        slotWidth = minSlotWidth
        val fullest = rows.maxByOrNull { it.size }
        if (stretchSlots && bounded && fullest != null) {
            slotWidth += ((available - rowWidth(fullest, minSlotWidth)) / fullest.size).coerceAtLeast(0)
        }

        val slotSpec = MeasureSpec.makeMeasureSpec(slotWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
        items.forEach { it.slot.measure(slotSpec, heightSpec) }

        val contentWidth = rows.maxOfOrNull { rowWidth(it) } ?: 0
        setMeasuredDimension(
            resolveSize(contentWidth + padding, widthMeasureSpec),
            resolveSize(rowHeight * rows.size + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val innerWidth = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        rows.forEachIndexed { rowIndex, row ->
            val rowTop = paddingTop + rowHeight * rowIndex
            val rowStart = (innerWidth - rowWidth(row)) / 2
            var offset = 0
            row.forEachIndexed { index, item ->
                place(item.slot, rowStart + offset, rowTop, innerWidth, rtl)
                offset += slotWidth + gap
                val divider = item.divider ?: return@forEachIndexed
                if (index == row.lastIndex) {
                    divider.layout(0, 0, 0, 0)
                } else {
                    place(divider, rowStart + offset, rowTop, innerWidth, rtl)
                    offset += dividerSpan(item)
                }
            }
        }
    }

    private fun place(child: View, start: Int, rowTop: Int, innerWidth: Int, rtl: Boolean) {
        val width = child.measuredWidth
        val left = paddingLeft + if (rtl) innerWidth - start - width else start
        val top = rowTop + (rowHeight - child.measuredHeight) / 2
        child.layout(left, top, left + width, top + child.measuredHeight)
    }
}
