package wtf.mazy.peel.ui.grouplist

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.binders.WebAppGroupBinder

class GroupListAdapter(
    actions: EntityRowActions<WebAppGroup>,
    @ColorInt checkIconColor: Int,
) : EntityListAdapter<WebAppGroup, GroupListAdapter.ViewHolder>(
    binder = WebAppGroupBinder,
    actions = actions,
    checkIconColor = checkIconColor,
) {

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        val iconSandbox: ImageView = itemView.findViewById(R.id.iconSandbox)
        val iconEphemeral: ImageView = itemView.findViewById(R.id.iconEphemeral)
        override val indicators: List<ImageView> = listOf(iconSandbox, iconEphemeral)
        val titleView: TextView = itemView.findViewById(R.id.item_primary)
    }

    override fun layoutRes(): Int = R.layout.group_list_item
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<WebAppGroup>) {
        val group = row.entity
        holder.titleView.text = group.title
        holder.iconSandbox.visibility = if (group.isUseContainer) View.VISIBLE else View.GONE
        holder.iconEphemeral.visibility =
            if (group.isUseContainer && group.isEphemeralSandbox) View.VISIBLE else View.GONE
    }
}
