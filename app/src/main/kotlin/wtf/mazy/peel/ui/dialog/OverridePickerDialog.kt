package wtf.mazy.peel.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SettingCategory
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.WebAppSettings

class OverridePickerDialog : DialogFragment() {

    private var listener: OnSettingSelectedListener? = null
    private var currentSettings: WebAppSettings? = null
    private var globalSettings: WebAppSettings? = null

    interface OnSettingSelectedListener {
        fun onSettingSelected(setting: SettingDefinition)
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SETTING = 1
        private const val ARG_CURRENT_SETTINGS = "current_settings"
        private const val ARG_GLOBAL_SETTINGS = "global_settings"

        fun newInstance(
            currentSettings: WebAppSettings,
            globalSettings: WebAppSettings,
            listener: OnSettingSelectedListener,
        ): OverridePickerDialog {
            return OverridePickerDialog().apply {
                this.listener = listener
                arguments =
                    Bundle().apply {
                        putString(ARG_CURRENT_SETTINGS, Json.encodeToString(currentSettings))
                        putString(ARG_GLOBAL_SETTINGS, Json.encodeToString(globalSettings))
                    }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (listener == null && context is OnSettingSelectedListener) {
            listener = context
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_CURRENT_SETTINGS)?.let {
            currentSettings = Json.decodeFromString(it)
        }
        arguments?.getString(ARG_GLOBAL_SETTINGS)?.let {
            globalSettings = Json.decodeFromString(it)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (currentSettings == null || listener == null) {
            dismissAllowingStateLoss()
            return AlertDialog.Builder(requireContext()).create()
        }

        val builder = AlertDialog.Builder(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_override_picker, null)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_settings)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val overriddenKeys = currentSettings?.getOverriddenKeys()?.toSet() ?: emptySet()

        val settingsGrouped =
            SettingRegistry.getAllSettings()
                .filter { it.key !in overriddenKeys }
                .groupBy { it.category }
                .toSortedMap(compareBy { it.ordinal })

        recyclerView.adapter =
            SettingsAdapter(settingsGrouped) { setting ->
                listener?.onSettingSelected(setting)
                dismiss()
            }

        builder.setView(view)
        builder.setTitle(R.string.select_setting_to_override)

        return builder.create()
    }

    sealed class ListItem {
        data class Header(val category: SettingCategory) : ListItem()

        data class Setting(val definition: SettingDefinition) : ListItem()
    }

    private class SettingsAdapter(
        settingsGrouped: Map<SettingCategory, List<SettingDefinition>>,
        private val onItemClick: (SettingDefinition) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<ListItem>()

        init {
            settingsGrouped.forEach { (category, settings) ->
                items.add(ListItem.Header(category))
                settings.forEach { items.add(ListItem.Setting(it)) }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Header -> VIEW_TYPE_HEADER
                is ListItem.Setting -> VIEW_TYPE_SETTING
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_HEADER -> {
                    val view =
                        LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_picker_category_header, parent, false)
                    HeaderViewHolder(view)
                }

                else -> {
                    val view =
                        LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_setting_picker, parent, false)
                    SettingViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header ->
                    (holder as HeaderViewHolder).bind(item.category, position == 0)

                is ListItem.Setting -> (holder as SettingViewHolder).bind(item.definition)
            }
        }

        override fun getItemCount() = items.size

        private inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.text_category)
            private val divider: View = view.findViewById(R.id.divider)

            fun bind(category: SettingCategory, isFirst: Boolean) {
                textView.text = itemView.context.getString(category.displayNameResId)
                divider.visibility = if (isFirst) View.GONE else View.VISIBLE
            }
        }

        private inner class SettingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textName: TextView = view.findViewById(R.id.text_setting_name)

            fun bind(setting: SettingDefinition) {
                textName.text = itemView.context.getString(setting.displayNameResId)

                itemView.setOnClickListener { onItemClick(setting) }
            }
        }
    }
}
