package wtf.mazy.peel.ui.entitylist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import wtf.mazy.peel.model.SyncExecutor

abstract class EntityListAdapter<T : Any, VH : EntityListViewHolder>(
    private val binder: EntityBinder<T>,
    private val actions: EntityRowActions<T>,
    @get:ColorInt private val checkIconColor: Int,
) : ListAdapter<EntityRow<T>, VH>(buildDiffer(binder)) {

    init {
        setHasStableIds(true)
    }

    final override fun getItemId(position: Int): Long = binder.stableId(getItem(position).entity)

    @LayoutRes
    protected abstract fun layoutRes(): Int
    protected abstract fun createViewHolder(view: View): VH
    protected abstract fun bindRow(holder: VH, row: EntityRow<T>)

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes(), parent, false)
        return createViewHolder(view)
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        bindRow(holder, row)
        binder.bindIcon(holder, row.entity, row.selected, checkIconColor)
        EntityRowAnimator.applyModeState(holder, row.inSelectionMode)
        attachClickListeners(holder, row)
    }

    final override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val tags = mergedRowPayload(payloads)
        if (tags.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val row = getItem(position)
        if (PAYLOAD_SELECTION in tags) {
            binder.animateIconSwap(holder, row.entity, row.selected, checkIconColor)
        }
        if (PAYLOAD_MODE in tags) {
            EntityRowAnimator.animateModeTransition(holder, row.inSelectionMode)
            attachClickListeners(holder, row)
        }
    }

    private fun attachClickListeners(holder: VH, row: EntityRow<T>) {
        if (row.inSelectionMode) {
            holder.menuButton.isEnabled = false
            holder.menuButton.setOnClickListener(null)
            holder.itemView.setOnClickListener { actions.onItemIconClick(row.entity) }
            holder.itemIcon.setOnClickListener { actions.onItemIconClick(row.entity) }
        } else {
            holder.menuButton.isEnabled = true
            holder.itemView.setOnClickListener { actions.onItemClick(row.entity) }
            holder.itemIcon.setOnClickListener { actions.onItemIconClick(row.entity) }
            holder.menuButton.setOnClickListener { v -> actions.onItemMenu(v, row.entity) }
        }
    }

    fun moveItem(from: Int, to: Int) {
        val mutable = currentList.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        submitList(mutable)
    }

    fun submitRows(rows: List<EntityRow<T>>) {
        submitList(rows)
    }

    companion object {
        private fun <T : Any> buildDiffer(binder: EntityBinder<T>): AsyncDifferConfig<EntityRow<T>> =
            AsyncDifferConfig.Builder(RowDiffCallback(binder))
                .setBackgroundThreadExecutor(SyncExecutor)
                .build()
    }

    private class RowDiffCallback<T : Any>(
        private val binder: EntityBinder<T>,
    ) : DiffUtil.ItemCallback<EntityRow<T>>() {
        override fun areItemsTheSame(oldItem: EntityRow<T>, newItem: EntityRow<T>): Boolean =
            binder.uuid(oldItem.entity) == binder.uuid(newItem.entity)

        override fun areContentsTheSame(oldItem: EntityRow<T>, newItem: EntityRow<T>): Boolean =
            binder.contentEquals(oldItem.entity, newItem.entity) &&
                    oldItem.selected == newItem.selected &&
                    oldItem.inSelectionMode == newItem.inSelectionMode &&
                    oldItem.tertiaryText == newItem.tertiaryText

        override fun getChangePayload(oldItem: EntityRow<T>, newItem: EntityRow<T>): Any? {
            if (!binder.contentEquals(oldItem.entity, newItem.entity)) return null
            if (oldItem.tertiaryText != newItem.tertiaryText) return null
            return entityRowChangePayload(
                selectionChanged = oldItem.selected != newItem.selected,
                modeChanged = oldItem.inSelectionMode != newItem.inSelectionMode,
            )
        }
    }
}
