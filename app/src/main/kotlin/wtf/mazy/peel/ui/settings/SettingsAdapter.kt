package wtf.mazy.peel.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebAppSettings

class SettingsAdapter(
    private val items: List<SettingsListItem>,
    private val settings: WebAppSettings,
    private val factory: SettingRowFactory,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int = when (val item = items[position]) {
        is SettingsListItem.Header -> R.layout.item_setting_category_header
        is SettingsListItem.Setting -> item.definition.layoutRes
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingsListItem.Header ->
                (holder.itemView as TextView).setText(item.category.displayNameResId)

            is SettingsListItem.Setting -> factory.bindView(
                holder.itemView,
                item.definition,
                settings,
                item.position,
            )
        }
    }

    override fun getItemCount() = items.size

}
