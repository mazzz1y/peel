package wtf.mazy.peel.ui.linksheet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import wtf.mazy.peel.R
import com.google.android.material.R as MaterialR

/** A rule under the current window's row, setting it apart from the web apps it can hand the link to. */
internal class CurrentWindowDivider(context: Context, private val after: (Int) -> Boolean) :
    RecyclerView.ItemDecoration() {

    private val resources = context.resources
    private val thickness = resources.getDimensionPixelSize(R.dimen.divider_height)
    private val gap = resources.getDimensionPixelSize(R.dimen.link_sheet_current_window_gap)
    private val cardMargin = resources.getDimensionPixelSize(R.dimen.list_card_margin_vertical)
    private val inset = resources.getDimensionPixelSize(R.dimen.list_card_margin_horizontal)
    private val paint = Paint().apply {
        color = MaterialColors.getColor(context, MaterialR.attr.colorOutlineVariant, 0)
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        if (after(parent.getChildAdapterPosition(view))) outRect.bottom = gap * 2 + thickness
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (!after(parent.getChildAdapterPosition(child))) continue
            // The card's own margin sits between its edge and the offset this decoration adds.
            val top = (child.bottom + cardMargin + gap + child.translationY).toInt()
            paint.alpha = (child.alpha * FAINT_ALPHA).toInt()
            val left = parent.paddingLeft + inset
            val right = parent.width - parent.paddingRight - inset
            val trim = (right - left) * EDGE_TRIM
            canvas.drawRect(left + trim, top.toFloat(), right - trim, (top + thickness).toFloat(), paint)
        }
    }

    private companion object {
        // Half the outline tone: the window and the web apps are one choice, only grouped.
        const val FAINT_ALPHA = 255 * 0.5f
        const val EDGE_TRIM = 0.03f
    }
}
