package wtf.mazy.peel.ui.proxylist

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.binders.ProxyBinder

class ProxyListAdapter(
    actions: EntityRowActions<Proxy>,
) : EntityListAdapter<Proxy, ProxyListAdapter.ViewHolder>(
    binder = ProxyBinder,
    actions = actions,
    checkIconColor = 0,
) {

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        override val indicators: List<ImageView> = emptyList()
        val primary: TextView = itemView.findViewById(R.id.item_primary)
        val secondary: TextView = itemView.findViewById(R.id.item_secondary)
    }

    override fun layoutRes(): Int = R.layout.proxy_list_item
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<Proxy>) {
        val proxy = row.entity
        holder.primary.text = proxy.displayName()
        holder.secondary.text = proxy.summary()
    }
}
