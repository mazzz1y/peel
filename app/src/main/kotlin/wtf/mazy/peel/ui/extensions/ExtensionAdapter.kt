package wtf.mazy.peel.ui.extensions

import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.R

class ExtensionAdapter(
    private val onUpdate: (WebExtension) -> Unit,
    private val onSettings: (WebExtension) -> Unit,
    private val onUninstall: (WebExtension) -> Unit,
) : RecyclerView.Adapter<ExtensionAdapter.ViewHolder>() {

    var items: List<WebExtension> = emptyList()
        private set

    fun submitList(newItems: List<WebExtension>) {
        items = newItems
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.extIcon)
        val name: TextView = itemView.findViewById(R.id.extName)
        val version: TextView = itemView.findViewById(R.id.extVersion)
        val menu: ImageView = itemView.findViewById(R.id.btnMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_extension, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ext = items[position]
        val meta = ext.metaData
        val extName = meta.name ?: ext.id
        holder.name.text = extName
        holder.version.text = meta.version

        ExtensionIconCache.bind(holder.icon, holder.itemView.context, ext.id, extName)

        holder.menu.setOnClickListener { v ->
            val popup = PopupMenu(v.context, v)
            popup.menu.add(Menu.NONE, MENU_UPDATE, 0, R.string.extension_update)
            if (meta.optionsPageUrl != null) {
                popup.menu.add(Menu.NONE, MENU_SETTINGS, 1, R.string.extension_settings)
            }
            popup.menu.add(Menu.NONE, MENU_UNINSTALL, 2, R.string.uninstall_extension)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_UPDATE -> onUpdate(ext)
                    MENU_SETTINGS -> onSettings(ext)
                    MENU_UNINSTALL -> onUninstall(ext)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount() = items.size

    companion object {
        private const val MENU_UPDATE = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_UNINSTALL = 3
    }
}
