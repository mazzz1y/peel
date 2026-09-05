package wtf.mazy.peel.ui.entitylist

import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R

abstract class EntityListViewHolder(itemView: View) :
    RecyclerView.ViewHolder(itemView), EntityRowView {
    override val itemIcon: ImageView = itemView.findViewById(R.id.item_icon)
    override val menuButton: View = itemView.findViewById(R.id.item_menu)

    init {
        linkRowFocus()
    }

    // Geometric focus search never offers the card back once a child of it holds focus.
    private fun linkRowFocus() {
        // itemView is inflated unattached, so its own layoutDirection still reads LTR here.
        val rtl = itemView.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val (start, end) = if (rtl) menuButton to itemIcon else itemIcon to menuButton
        itemView.nextFocusLeftId = start.id
        itemView.nextFocusRightId = end.id
        start.nextFocusRightId = itemView.id
        end.nextFocusLeftId = itemView.id
    }
}
