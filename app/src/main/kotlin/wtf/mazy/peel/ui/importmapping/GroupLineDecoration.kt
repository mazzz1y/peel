package wtf.mazy.peel.ui.importmapping

import android.graphics.Canvas
import android.graphics.Paint
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

class GroupLineDecoration : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var lineWidthPx = 0f
    private var iconCenterXPx = 0f
    private var initialized = false

    private fun init(parent: RecyclerView) {
        if (initialized) return
        val density = parent.resources.displayMetrics.density
        lineWidthPx = 2f * density
        // icon center: 12dp card margin + 16dp padding + 22dp half icon = 50dp
        iconCenterXPx = 50f * density
        paint.color = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOutlineVariant)
        initialized = true
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        init(parent)
        val adapter = parent.adapter as? ImportMappingAdapter ?: return
        val x = iconCenterXPx - lineWidthPx / 2f
        val childCount = parent.childCount

        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val nextChild = parent.getChildAt(i + 1)
            val pos = parent.getChildAdapterPosition(child)
            val nextPos = parent.getChildAdapterPosition(nextChild)
            if (pos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue

            val shouldConnect =
                (adapter.isExpandedGroupHeader(pos) && adapter.isGroupedApp(nextPos)) ||
                (adapter.isGroupedApp(pos) && !adapter.isLastGroupedApp(pos) && adapter.isGroupedApp(nextPos))

            if (shouldConnect) {
                val top = child.bottom.toFloat()
                val bottom = nextChild.top.toFloat()
                if (bottom > top) {
                    c.drawRect(x, top, x + lineWidthPx, bottom, paint)
                }
            }
        }
    }
}
