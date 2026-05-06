package wtf.mazy.peel.ui.extensions

import android.content.Context
import android.view.Menu
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.ColorInt
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions
import wtf.mazy.peel.ui.entitylist.binders.WebExtensionBinder

class ExtensionAdapter(
    context: Context,
    @ColorInt checkIconColor: Int,
    onUpdate: (WebExtension) -> Unit,
    onSettings: (WebExtension) -> Unit,
    onUninstall: (WebExtension) -> Unit,
) : EntityListAdapter<WebExtension, ExtensionAdapter.ViewHolder>(
    binder = WebExtensionBinder(context),
    actions = ExtensionItemActions(onUpdate, onSettings, onUninstall),
    checkIconColor = checkIconColor,
) {

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        override val indicators: List<ImageView> = emptyList()
        val name: TextView = itemView.findViewById(R.id.item_primary)
        val version: TextView = itemView.findViewById(R.id.item_secondary)
    }

    override fun layoutRes(): Int = R.layout.item_extension
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<WebExtension>) {
        val ext = row.entity
        val meta = ext.metaData
        holder.name.text = meta.name ?: ext.id
        holder.version.text = meta.version
    }

    private class ExtensionItemActions(
        private val onUpdate: (WebExtension) -> Unit,
        private val onSettings: (WebExtension) -> Unit,
        private val onUninstall: (WebExtension) -> Unit,
    ) : EntityRowActions<WebExtension> {
        override fun onItemClick(item: WebExtension) {}
        override fun onItemIconClick(item: WebExtension) {}
        override fun onItemMenu(view: View, item: WebExtension) {
            val popup = PopupMenu(view.context, view)
            popup.menu.add(Menu.NONE, MENU_UPDATE, 0, R.string.extension_update)
            if (item.metaData.optionsPageUrl != null) {
                popup.menu.add(Menu.NONE, MENU_SETTINGS, 1, R.string.extension_settings)
            }
            popup.menu.add(Menu.NONE, MENU_UNINSTALL, 2, R.string.uninstall_extension)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_UPDATE -> onUpdate(item)
                    MENU_SETTINGS -> onSettings(item)
                    MENU_UNINSTALL -> onUninstall(item)
                }
                true
            }
            popup.show()
        }
    }

    companion object {
        private const val MENU_UPDATE = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_UNINSTALL = 3
    }
}
