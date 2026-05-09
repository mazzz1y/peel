package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.ParsedBackup
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.dialog.showSandboxInputDialog
import wtf.mazy.peel.ui.importmapping.ImportMappingAdapter
import wtf.mazy.peel.util.withBoldSpan

class ImportActivity : AppCompatActivity() {

    private var mappingAdapter: ImportMappingAdapter? = null
    private var selectedGroupUuid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        val parsed = pendingBackup ?: run { finish(); return }
        pendingBackup = null

        val groupShareMode = intent.getBooleanExtra(EXTRA_GROUP_SHARE, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_mapping_title)
        toolbar.setNavigationOnClickListener { finish() }

        val websites = parsed.backupData.websites
        val icons = parsed.icons
        val isEmpty = websites.isEmpty()
        val singleApp = websites.size == 1

        val fab = findViewById<FloatingActionButton>(R.id.fab_import)
        if (isEmpty) fab.hide()
        fab.setOnClickListener {
            val adapter = mappingAdapter ?: return@setOnClickListener
            val selectedUuids = adapter.selectedUuids.toSet()
            if (groupShareMode) {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(RESULT_SELECTED_UUIDS, selectedUuids.toTypedArray())
                    putExtra(RESULT_SELECTED_GROUP_UUIDS, adapter.selectedGroupUuids.toTypedArray())
                })
            } else {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(RESULT_SELECTED_UUIDS, selectedUuids.toTypedArray())
                    putExtra(RESULT_GROUP_UUID, selectedGroupUuid)
                })
            }
            finish()
        }

        val descriptionView = findViewById<TextView>(R.id.import_mapping_description)
        val groupLayout = findViewById<View>(R.id.destination_group_layout)
        val dropdown = findViewById<AutoCompleteTextView>(R.id.destination_group_dropdown)
        val recycler = findViewById<RecyclerView>(R.id.import_app_list)
        val emptyView = findViewById<View>(R.id.import_mapping_empty)

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
                ArrayAdapter(this, android.R.layout.simple_list_item_1, groupLabels)
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
                    showSandboxInputDialog(
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
                        lifecycleScope.launch {
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

        val appsText =
            resources.getQuantityString(R.plurals.import_apps_count, websites.size, websites.size)
        val descriptionRes = when {
            groupShareMode -> R.string.import_group_share_description
            hasGroups && !singleApp -> R.string.import_mapping_description
            hasGroups && singleApp -> R.string.import_mapping_description_single
            !hasGroups && !singleApp -> R.string.import_mapping_description_no_groups
            else -> R.string.import_mapping_description_single_no_groups
        }
        if (groupShareMode) {
            val groupsCount = parsed.backupData.groups.size
            val groupsText =
                resources.getQuantityString(R.plurals.import_groups_count, groupsCount, groupsCount)
            if (groupsCount > 1) {
                descriptionView.text = getString(
                    R.string.import_group_share_description_multi,
                    groupsText,
                    appsText,
                )
            } else {
                val groupName = parsed.backupData.groups.firstOrNull()?.title?.ifBlank {
                    getString(R.string.group)
                } ?: getString(R.string.group)
                descriptionView.text =
                    getString(descriptionRes, groupName, appsText).withBoldSpan(groupName)
            }
        } else {
            descriptionView.text = getString(descriptionRes, appsText)
        }

        val selectedUuids = websites.mapTo(mutableSetOf()) { it.uuid }
        val selectedGroupUuids = mutableSetOf<String>()
        val groupSections = if (groupShareMode) {
            parsed.backupData.groups.map { group ->
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
        recycler.layoutManager = LinearLayoutManager(this)
        (recycler.itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
        recycler.adapter = mappingAdapter

        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_GROUP_SHARE = "group_share"
        const val RESULT_SELECTED_UUIDS = "selected_uuids"
        const val RESULT_SELECTED_GROUP_UUIDS = "selected_group_uuids"
        const val RESULT_GROUP_UUID = "group_uuid"

        private const val CREATE_GROUP_SENTINEL = "__create_group__"

        @Volatile
        var pendingBackup: ParsedBackup? = null
    }
}
