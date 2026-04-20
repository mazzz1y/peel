package wtf.mazy.peel.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppSettings

class SettingsAdapter(
    private val items: List<SettingsListItem>,
    private val settings: WebAppSettings,
    private val factory: SettingViewFactory,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int = when (val item = items[position]) {
        is SettingsListItem.Header -> TYPE_HEADER
        is SettingsListItem.Divider -> TYPE_DIVIDER
        is SettingsListItem.Setting -> when (item.definition) {
            is SettingDefinition.BooleanSetting -> TYPE_BOOLEAN
            is SettingDefinition.ChoiceSetting -> TYPE_DROPDOWN
            is SettingDefinition.BooleanWithIntSetting -> TYPE_BOOLEAN_INT
            is SettingDefinition.BooleanWithCredentialsSetting -> TYPE_BOOLEAN_CREDENTIALS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layoutRes = when (viewType) {
            TYPE_HEADER -> R.layout.item_setting_category_header
            TYPE_DIVIDER -> R.layout.item_settings_divider
            TYPE_BOOLEAN -> R.layout.item_setting_boolean
            TYPE_DROPDOWN -> R.layout.item_setting_dropdown
            TYPE_BOOLEAN_INT -> R.layout.item_setting_boolean_int
            TYPE_BOOLEAN_CREDENTIALS -> R.layout.item_setting_boolean_credentials
            else -> error("Unknown view type $viewType")
        }
        val view = inflater.inflate(layoutRes, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingsListItem.Header -> {
                (holder.itemView as TextView).setText(item.category.displayNameResId)
            }
            is SettingsListItem.Divider -> Unit
            is SettingsListItem.Setting -> factory.bindView(holder.itemView, item.definition, settings)
        }
    }

    override fun getItemCount() = items.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_DIVIDER = 1
        private const val TYPE_BOOLEAN = 2
        private const val TYPE_DROPDOWN = 3
        private const val TYPE_BOOLEAN_INT = 4
        private const val TYPE_BOOLEAN_CREDENTIALS = 5
    }
}
