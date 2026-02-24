package wtf.mazy.peel.ui

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

fun dragReorderCallback(
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onPickUp: ((View) -> Unit)? = null,
    onRelease: ((View) -> Unit)? = null,
): ItemTouchHelper.Callback = object : ItemTouchHelper.Callback() {

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        onMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun isLongPressDragEnabled() = true

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (onPickUp != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.let { onPickUp(it) }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        onRelease?.invoke(viewHolder.itemView)
        onDrop()
    }
}
