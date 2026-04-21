package wtf.mazy.peel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R

class PickerRecyclerAdapter<T>(
    private val items: List<T>,
    private val onClick: (T) -> Unit,
    private val bind: (item: T, icon: ImageView, name: TextView, detail: TextView) -> Unit,
) : RecyclerView.Adapter<PickerRecyclerAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.appIcon)
        val name: TextView = itemView.findViewById(R.id.appName)
        val detail: TextView = itemView.findViewById(R.id.groupName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_share_picker, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        bind(item, holder.icon, holder.name, holder.detail)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
