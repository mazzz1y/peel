package wtf.mazy.peel.ui.entitylist

import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R

abstract class EntityListViewHolder(itemView: View) :
    RecyclerView.ViewHolder(itemView), EntityRowView {
    override val itemIcon: ImageView = itemView.findViewById(R.id.item_icon)
    override val menuButton: ImageView = itemView.findViewById(R.id.item_menu)
}
