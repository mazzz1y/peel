package wtf.mazy.peel.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.importmapping.ImportMappingAdapter

class ImportBottomSheetFragment : BottomSheetDialogFragment() {

    private var parsed: ParsedBackup? = null
    private var onImport: ((selectedUuids: Set<String>, groupUuid: String?) -> Unit)? = null
    private var onGroupImport: ((selectedUuids: Set<String>, selectedGroupUuids: Set<String>) -> Unit)? =
        null
    private var groupShareMode = false

    private var selectedGroupUuid: String? = null
    private var mappingAdapter: ImportMappingAdapter? = null

    fun configure(
        parsed: ParsedBackup,
        onImport: (selectedUuids: Set<String>, groupUuid: String?) -> Unit,
    ) {
        groupShareMode = false
        this.parsed = parsed
        this.onImport = onImport
        this.onGroupImport = null
    }

    fun configureGroupShare(
        parsed: ParsedBackup,
        onImport: (selectedUuids: Set<String>, selectedGroupUuids: Set<String>) -> Unit,
    ) {
        groupShareMode = true
        this.parsed = parsed
        this.onImport = null
        this.onGroupImport = onImport
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_import_shared, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val backup = parsed ?: run { dismissAllowingStateLoss(); return }
        val websites = backup.backupData.websites
        val icons = backup.icons
        val isEmpty = websites.isEmpty()
        val singleApp = websites.size == 1
        val hostActivity = requireActivity()

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val descriptionView = view.findViewById<TextView>(R.id.import_mapping_description)
        val groupLayout = view.findViewById<View>(R.id.destination_group_layout)
        val dropdown = view.findViewById<AutoCompleteTextView>(R.id.destination_group_dropdown)
        val recycler = view.findViewById<RecyclerView>(R.id.import_app_list)
        val emptyView = view.findViewById<View>(R.id.import_mapping_empty)

        toolbar.setNavigationOnClickListener { dismissAllowingStateLoss() }
        toolbar.inflateMenu(R.menu.menu_import_sheet)
        val importItem = toolbar.menu.findItem(R.id.action_import)
        importItem.isEnabled = !isEmpty
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_import) {
                val adapter = mappingAdapter ?: return@setOnMenuItemClickListener false
                val selectedUuids = adapter.selectedUuids.toSet()
                if (groupShareMode) {
                    onGroupImport?.invoke(selectedUuids, adapter.selectedGroupUuids.toSet())
                } else {
                    onImport?.invoke(selectedUuids, selectedGroupUuid)
                }
                dismissAllowingStateLoss()
                true
            } else false
        }

        val groups = DataManager.instance.sortedGroups
        val hasGroups = groups.isNotEmpty()
        val groupValues = mutableListOf<String?>()
        val groupLabels = mutableListOf<String>()
        groups.forEach { group ->
            groupValues.add(group.uuid)
            groupLabels.add(group.title)
        }

        if (groupShareMode) {
            groupLayout.visibility = View.GONE
        } else if (!hasGroups) {
            groupLayout.visibility = View.GONE
        } else {
            selectedGroupUuid = groups[0].uuid
            groupValues.add(null)
            groupLabels.add(getString(R.string.ungrouped))
            groupValues.add(CREATE_GROUP_SENTINEL)
            groupLabels.add(getString(R.string.import_group_mode_new))

            val dropdownAdapter =
                ArrayAdapter(hostActivity, android.R.layout.simple_list_item_1, groupLabels)
            dropdown.setAdapter(dropdownAdapter)
            dropdown.setText(groupLabels[0], false)

            dropdown.setOnItemClickListener { _, _, position, _ ->
                val value = groupValues[position]
                if (value == CREATE_GROUP_SENTINEL) {
                    dropdown.setText(
                        groupLabels.getOrNull(groupValues.indexOf(selectedGroupUuid))
                            ?: groupLabels[0],
                        false,
                    )
                    hostActivity.showSandboxInputDialog(
                        titleRes = R.string.add_group,
                        hintRes = R.string.group_name_hint,
                    ) { result ->
                        val title = result.text.trim()
                        if (title.isEmpty()) return@showSandboxInputDialog
                        val group = WebAppGroup(
                            title = title,
                            order = DataManager.instance.getGroups().size,
                        )
                        group.isUseContainer = result.sandbox
                        group.isEphemeralSandbox = result.ephemeral
                        viewLifecycleOwner.lifecycleScope.launch {
                            DataManager.instance.addGroup(group)
                            selectedGroupUuid = group.uuid

                            groupValues.add(groupValues.size - 2, group.uuid)
                            groupLabels.add(groupLabels.size - 2, group.title)
                            dropdownAdapter.notifyDataSetChanged()
                            dropdownAdapter.filter.filter(null)
                            dropdown.setText(group.title, false)
                        }
                    }
                    return@setOnItemClickListener
                }
                selectedGroupUuid = value
            }
        }

        val descriptionRes = when {
            groupShareMode -> R.string.import_group_share_description
            hasGroups && !singleApp -> R.string.import_mapping_description
            hasGroups && singleApp -> R.string.import_mapping_description_single
            !hasGroups && !singleApp -> R.string.import_mapping_description_no_groups
            else -> R.string.import_mapping_description_single_no_groups
        }
        if (groupShareMode) {
            val groupsCount = backup.backupData.groups.size
            if (groupsCount > 1) {
                descriptionView.text = getString(
                    R.string.import_group_share_description_multi,
                    groupsCount,
                    websites.size,
                )
            } else {
                val groupName = backup.backupData.groups.firstOrNull()?.title?.ifBlank {
                    getString(R.string.group)
                } ?: getString(R.string.group)
                descriptionView.text = getString(descriptionRes, groupName, websites.size)
            }
        } else {
            descriptionView.text = getString(descriptionRes, websites.size)
        }

        val selectedUuids = websites.mapTo(mutableSetOf()) { it.uuid }
        val selectedGroupUuids = mutableSetOf<String>()
        val groupSections = if (groupShareMode) {
            backup.backupData.groups.map { group ->
                selectedGroupUuids.add(group.uuid)
                ImportMappingAdapter.GroupSection(
                    uuid = group.uuid,
                    title = group.title.ifBlank { getString(R.string.group) },
                    apps = websites.filter { it.groupUuid == group.uuid },
                )
            }
        } else {
            emptyList()
        }
        mappingAdapter = ImportMappingAdapter(
            items = websites,
            icons = icons,
            selectedUuids = selectedUuids,
            showSwitches = groupShareMode || !singleApp,
            groupSections = groupSections,
            selectedGroupUuids = selectedGroupUuids,
        )
        recycler.layoutManager = LinearLayoutManager(hostActivity)
        recycler.adapter = mappingAdapter

        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet.requestLayout()
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.isDraggable = false
        behavior.isFitToContents = false
        behavior.halfExpandedRatio = 0.85f
        behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        behavior.skipCollapsed = true
    }

    override fun onDestroyView() {
        mappingAdapter = null
        onGroupImport = null
        onImport = null
        super.onDestroyView()
    }

    companion object {
        private const val CREATE_GROUP_SENTINEL = "__create_group__"
        const val TAG = "ImportBottomSheet"
    }
}
