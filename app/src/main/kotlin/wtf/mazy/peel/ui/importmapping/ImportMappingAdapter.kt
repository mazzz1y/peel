package wtf.mazy.peel.ui.importmapping

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import wtf.mazy.peel.R
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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class GroupSection(
        val uuid: String,
        val title: String,
        val apps: List<WebAppSurrogate>,
    )

    private sealed interface Row {
        data class GroupHeader(val section: GroupSection) : Row
        data class AppItem(val sectionUuid: String, val app: WebAppSurrogate) : Row
    }

    private val expandedGroups = mutableSetOf<String>()
    private val selectedAppsByGroup = mutableMapOf<String, MutableSet<String>>()
    private val rows = mutableListOf<Row>()

    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.group_icon)
        val name: TextView = itemView.findViewById(R.id.group_name)
        val count: TextView = itemView.findViewById(R.id.group_count)
        val chevron: ImageView = itemView.findViewById(R.id.group_expand)
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.group_checkbox)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.app_icon)
        val name: TextView = itemView.findViewById(R.id.app_name)
        val url: TextView = itemView.findViewById(R.id.app_url)
        val checkbox: MaterialCheckBox = itemView.findViewById(R.id.app_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_GROUP -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_import_group_header, parent, false)
                GroupViewHolder(view)
            }

            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_import_mapping, parent, false)
                ViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is GroupViewHolder) {
            bindGroup(holder)
            return
        }
        if (holder !is ViewHolder) return

        val row = rows.getOrNull(position) as? Row.AppItem ?: return
        val item = row.app
        holder.name.text = item.title.ifBlank { item.baseUrl }
        holder.url.text = displayUrl(item.baseUrl)

        val bitmap = icons[item.uuid]
        if (bitmap != null) {
            holder.icon.setImageBitmap(bitmap)
        } else {
            val fallback =
                LetterIconGenerator.generate(
                    item.title.ifBlank { item.baseUrl },
                    item.title.ifBlank { item.baseUrl },
                    ICON_SIZE_PX,
                )
            holder.icon.setImageBitmap(fallback)
        }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.uuid in selectedUuids
        if (showSwitches) {
            holder.checkbox.visibility = View.VISIBLE
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedUuids.add(item.uuid)
                    if (row.sectionUuid.isNotEmpty()) {
                        selectedAppsByGroup.getOrPut(row.sectionUuid) { mutableSetOf() }
                            .add(item.uuid)
                        if (row.sectionUuid !in selectedGroupUuids) {
                            selectedGroupUuids.add(row.sectionUuid)
                            notifyDataSetChanged()
                        }
                    }
                } else {
                    selectedUuids.remove(item.uuid)
                    if (row.sectionUuid.isNotEmpty()) {
                        selectedAppsByGroup.getOrPut(row.sectionUuid) { mutableSetOf() }
                            .remove(item.uuid)
                    }
                }
            }
            holder.itemView.setOnClickListener { holder.checkbox.toggle() }
        } else {
            holder.checkbox.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int {
        return rows.size
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows.getOrNull(position)) {
            is Row.GroupHeader -> VIEW_TYPE_GROUP
            is Row.AppItem -> VIEW_TYPE_APP
            null -> VIEW_TYPE_APP
        }
    }

    fun isGroupedApp(position: Int): Boolean {
        val row = rows.getOrNull(position) as? Row.AppItem ?: return false
        return row.sectionUuid.isNotEmpty()
    }

    fun isExpandedGroupHeader(position: Int): Boolean {
        val row = rows.getOrNull(position) as? Row.GroupHeader ?: return false
        return row.section.uuid in expandedGroups && row.section.apps.isNotEmpty()
    }

    fun isLastGroupedApp(position: Int): Boolean {
        if (!isGroupedApp(position)) return false
        val next = rows.getOrNull(position + 1)
        return next == null || next !is Row.AppItem || next.sectionUuid.isEmpty()
    }

    private fun bindGroup(holder: GroupViewHolder) {
        val row = rows.getOrNull(holder.bindingAdapterPosition) as? Row.GroupHeader ?: return
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
        val isExpanded = section.uuid in expandedGroups
        val isSelected = section.uuid in selectedGroupUuids
        holder.chevron.rotation = if (isExpanded) 180f else 0f
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = isSelected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selectedGroupUuids.add(section.uuid)
                val selectedInGroup = selectedAppsByGroup.getOrPut(section.uuid) { mutableSetOf() }
                if (selectedInGroup.isEmpty()) {
                    section.apps.forEach {
                        selectedInGroup.add(it.uuid)
                        selectedUuids.add(it.uuid)
                    }
                } else {
                    selectedInGroup.forEach { selectedUuids.add(it) }
                }
            } else {
                selectedGroupUuids.remove(section.uuid)
                val selectedInGroup = selectedAppsByGroup.getOrPut(section.uuid) { mutableSetOf() }
                selectedInGroup.clear()
                section.apps.forEach { selectedUuids.remove(it.uuid) }
            }
            notifyDataSetChanged()
        }
        holder.itemView.setOnClickListener {
            if (section.uuid in expandedGroups) expandedGroups.remove(section.uuid)
            else expandedGroups.add(section.uuid)
            rebuildRows()
            notifyDataSetChanged()
        }
    }

    private fun rebuildRows() {
        rows.clear()
        if (groupSections.isEmpty()) {
            items.forEach { rows.add(Row.AppItem(sectionUuid = "", app = it)) }
            return
        }
        groupSections.forEach { section ->
            rows.add(Row.GroupHeader(section))
            if (section.uuid in expandedGroups) {
                section.apps.forEach { app ->
                    rows.add(Row.AppItem(sectionUuid = section.uuid, app = app))
                }
            }
        }
    }

    init {
        if (groupSections.isEmpty()) {
            selectedGroupUuids.clear()
            rebuildRows()
        } else {
            groupSections.forEach { section ->
                selectedAppsByGroup[section.uuid] = section.apps.mapTo(mutableSetOf()) { it.uuid }
            }
            if (selectedGroupUuids.isEmpty()) {
                selectedGroupUuids.addAll(groupSections.map { it.uuid })
            }
            if (selectedUuids.isEmpty()) {
                groupSections.flatMap { it.apps }.forEach { selectedUuids.add(it.uuid) }
            }
            rebuildRows()
        }
    }

    companion object {
        private const val ICON_SIZE_PX = 72
        private const val VIEW_TYPE_GROUP = 0
        private const val VIEW_TYPE_APP = 1
    }
}
