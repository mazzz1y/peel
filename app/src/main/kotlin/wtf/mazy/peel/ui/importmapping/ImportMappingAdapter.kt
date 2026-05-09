package wtf.mazy.peel.ui.importmapping

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import wtf.mazy.peel.R
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.model.WebAppSurrogate
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.util.displayUrl

class ImportMappingAdapter(
    private val items: List<WebAppSurrogate>,
    private val icons: Map<String, Bitmap>,
    val selectedUuids: MutableSet<String>,
    private val showSwitches: Boolean = true,
    private val groupSections: List<GroupSection> = emptyList(),
    val selectedGroupUuids: MutableSet<String> = mutableSetOf(),
) : ListAdapter<ImportMappingAdapter.Row, RecyclerView.ViewHolder>(RowDiffCallback()) {

    data class GroupSection(
        val uuid: String,
        val title: String,
        val apps: List<WebAppSurrogate>,
    )

    sealed interface Row {
        val rowKey: String

        data class GroupHeader(
            val section: GroupSection,
            val expanded: Boolean,
            val selected: Boolean,
        ) : Row {
            override val rowKey: String get() = "g:${section.uuid}"
        }

        data class AppItem(
            val sectionUuid: String?,
            val app: WebAppSurrogate,
            val selected: Boolean,
        ) : Row {
            override val rowKey: String get() = "a:${sectionUuid ?: ""}:${app.uuid}"
        }
    }

    private val expandedGroups = mutableSetOf<String>()

    init {
        setHasStableIds(true)
        if (groupSections.isEmpty()) {
            selectedGroupUuids.clear()
        } else {
            if (selectedGroupUuids.isEmpty()) {
                selectedGroupUuids.addAll(groupSections.map { it.uuid })
            }
            if (selectedUuids.isEmpty()) {
                groupSections.flatMap { it.apps }.forEach { selectedUuids.add(it.uuid) }
            }
        }
        submit()
    }

    override fun getItemId(position: Int): Long = StableIdRegistry.idFor(getItem(position).rowKey)

    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.group_icon)
        val name: TextView = itemView.findViewById(R.id.group_name)
        val count: TextView = itemView.findViewById(R.id.group_count)
        val chevron: ImageView = itemView.findViewById(R.id.group_expand)
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.group_checkbox)
    }

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val name: TextView = itemView.findViewById(R.id.app_name)
        val url: TextView = itemView.findViewById(R.id.app_url)
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.app_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_GROUP ->
                GroupViewHolder(inflater.inflate(R.layout.item_import_group_header, parent, false))

            VIEW_TYPE_GROUP_CHILD ->
                AppViewHolder(inflater.inflate(R.layout.item_import_group_child, parent, false))

            else ->
                AppViewHolder(inflater.inflate(R.layout.item_import_mapping, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.GroupHeader -> bindGroup(holder as GroupViewHolder, row)
            is Row.AppItem -> bindApp(holder as AppViewHolder, row)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val row = getItem(position)) {
            is Row.GroupHeader -> VIEW_TYPE_GROUP
            is Row.AppItem -> if (row.sectionUuid != null) VIEW_TYPE_GROUP_CHILD else VIEW_TYPE_APP
        }
    }

    private fun bindApp(holder: AppViewHolder, row: Row.AppItem) {
        val item = row.app
        holder.name.text = item.title.ifBlank { item.baseUrl }
        holder.url.text = displayUrl(item.baseUrl)

        val bitmap = icons[item.uuid]
        if (bitmap != null) {
            holder.icon.setImageBitmap(bitmap)
        } else {
            val label = item.title.ifBlank { item.baseUrl }
            holder.icon.setImageBitmap(LetterIconGenerator.generate(label, label, ICON_SIZE_PX))
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = row.selected
        if (showSwitches) {
            holder.checkbox.visibility = View.VISIBLE
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                val section = row.sectionUuid
                if (isChecked) {
                    selectedUuids.add(item.uuid)
                    if (section != null) selectedGroupUuids.add(section)
                } else {
                    selectedUuids.remove(item.uuid)
                }
                submit()
            }
            holder.itemView.setOnClickListener { holder.checkbox.toggle() }
        } else {
            holder.checkbox.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        }
    }

    private fun bindGroup(holder: GroupViewHolder, row: Row.GroupHeader) {
        val section = row.section
        holder.name.text = section.title
        holder.count.text = holder.itemView.context.resources.getQuantityString(
            R.plurals.group_app_count,
            section.apps.size,
            section.apps.size,
        )

        val bitmap = icons[section.uuid]
        if (bitmap != null) {
            holder.icon.setImageBitmap(bitmap)
        } else {
            holder.icon.setImageBitmap(
                LetterIconGenerator.generate(section.title, section.title, ICON_SIZE_PX)
            )
        }
        holder.chevron.rotation = if (row.expanded) 180f else 0f
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = row.selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selectedGroupUuids.add(section.uuid)
                section.apps.forEach { selectedUuids.add(it.uuid) }
            } else {
                selectedGroupUuids.remove(section.uuid)
                section.apps.forEach { selectedUuids.remove(it.uuid) }
            }
            submit()
        }
        holder.itemView.setOnClickListener {
            if (section.uuid in expandedGroups) expandedGroups.remove(section.uuid)
            else expandedGroups.add(section.uuid)
            submit()
        }
    }

    private fun buildRows(): List<Row> {
        if (groupSections.isEmpty()) {
            return items.map {
                Row.AppItem(
                    sectionUuid = null,
                    app = it,
                    selected = it.uuid in selectedUuids,
                )
            }
        }
        val out = mutableListOf<Row>()
        groupSections.forEach { section ->
            out.add(
                Row.GroupHeader(
                    section = section,
                    expanded = section.uuid in expandedGroups,
                    selected = section.uuid in selectedGroupUuids,
                )
            )
            if (section.uuid in expandedGroups) {
                section.apps.forEach { app ->
                    out.add(
                        Row.AppItem(
                            sectionUuid = section.uuid,
                            app = app,
                            selected = app.uuid in selectedUuids,
                        )
                    )
                }
            }
        }
        return out
    }

    private fun submit() {
        submitList(buildRows())
    }

    private class RowDiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.rowKey == newItem.rowKey
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    companion object {
        private const val ICON_SIZE_PX = 72
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_APP = 1
        private const val VIEW_TYPE_GROUP_CHILD = 2
    }
}
