package wtf.mazy.peel.ui.push

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import wtf.mazy.peel.R
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.model.db.PushSubscriptionEntity
import wtf.mazy.peel.ui.entitylist.EntityBinder
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.EntityRowView

data class PushSubscriptionItem(
    val key: String,
    val permission: ContentPermission?,
    val subscription: PushSubscriptionEntity?,
    val host: String,
    val sandboxTitle: String?,
    val allowed: Boolean,
    val icon: Bitmap,
)

object PushSubscriptionBinder : EntityBinder<PushSubscriptionItem> {
    override fun uuid(item: PushSubscriptionItem): String = item.key
    override fun stableId(item: PushSubscriptionItem): Long = StableIdRegistry.idFor(item.key)

    override fun bindIcon(
        host: EntityRowView,
        item: PushSubscriptionItem,
        selected: Boolean,
        checkIconColor: Int,
    ) {
        host.itemIcon.setImageBitmap(item.icon)
    }

    override fun contentEquals(a: PushSubscriptionItem, b: PushSubscriptionItem): Boolean =
        a.subscription == b.subscription &&
                a.sandboxTitle == b.sandboxTitle &&
                a.allowed == b.allowed
}

class PushSubscriptionListAdapter(
    actions: EntityRowActions<PushSubscriptionItem>,
) : EntityListAdapter<PushSubscriptionItem, PushSubscriptionListAdapter.ViewHolder>(
    binder = PushSubscriptionBinder,
    actions = actions,
    checkIconColor = 0,
) {

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        val statusIcon: ImageView = itemView.findViewById(R.id.iconPushStatus)
        override val indicators: List<ImageView> = listOf(statusIcon)
        val primary: TextView = itemView.findViewById(R.id.item_primary)
        val secondary: TextView = itemView.findViewById(R.id.item_secondary)
    }

    override fun layoutRes(): Int = R.layout.push_subscription_item
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<PushSubscriptionItem>) {
        val item = row.entity
        val context = holder.itemView.context
        val local = item.allowed && item.subscription == null
        val statusRes = when {
            !item.allowed -> R.string.push_status_denied
            local -> R.string.push_status_local
            else -> R.string.push_status_allowed
        }
        holder.primary.text = item.sandboxTitle ?: context.getString(R.string.push_scope_none)
        holder.secondary.text = item.host
        holder.statusIcon.setImageResource(
            when {
                !item.allowed -> R.drawable.ic_symbols_notifications_off_24
                local -> R.drawable.ic_symbols_cloud_off_24
                else -> R.drawable.ic_symbols_notifications_24
            }
        )
        holder.statusIcon.contentDescription = context.getString(statusRes)
    }
}
