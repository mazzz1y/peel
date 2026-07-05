package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.ItemSettingsHubBinding
import wtf.mazy.peel.databinding.SettingsHubBinding
import wtf.mazy.peel.model.ApplyTiming
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.SettingSection

class SettingsHubActivity : ToolbarBaseActivity<SettingsHubBinding>() {

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) forwardApplyTiming(result.data)
        }

    private val actions = SettingsHubActions(this)

    private var pendingTiming: ApplyTiming? = null

    override fun inflateBinding(layoutInflater: LayoutInflater): SettingsHubBinding =
        SettingsHubBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.action_settings))

        binding.recyclerHub.layoutManager = LinearLayoutManager(this)
        binding.recyclerHub.adapter = HubAdapter(entries()) { it.onClick() }
    }

    override fun onDestroy() {
        actions.onDestroy()
        super.onDestroy()
    }

    override fun finish() {
        pendingTiming?.let { timing ->
            setResult(
                RESULT_OK,
                Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name),
            )
        }
        super.finish()
    }

    private fun forwardApplyTiming(data: Intent?) {
        val name = data?.getStringExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING) ?: return
        val timing = runCatching { ApplyTiming.valueOf(name) }.getOrNull() ?: return
        pendingTiming = maxOf(timing, pendingTiming ?: timing)
    }

    private fun openSection(section: SettingSection) {
        settingsLauncher.launch(SettingsActivity.intentForSection(this, section))
    }

    private fun entries(): List<HubEntry> = listOf(
        HubEntry(R.string.global_settings, R.string.settings_section_global_summary, R.drawable.ic_symbols_tune_24) {
            openSection(SettingSection.GLOBAL)
        },
        HubEntry(R.string.settings_section_engine, R.string.settings_section_engine_summary, R.drawable.ic_symbols_memory_24) {
            openSection(SettingSection.ENGINE)
        },
        HubEntry(R.string.proxies, R.string.settings_section_proxies_summary, R.drawable.ic_symbols_dns_24) {
            startActivity(Intent(this, ProxyListActivity::class.java))
        },
        HubEntry(R.string.extensions, R.string.settings_section_extensions_summary, R.drawable.ic_symbols_extension_24) {
            startActivity(Intent(this, ExtensionsActivity::class.java))
        },
        HubEntry(R.string.groups, R.string.settings_section_groups_summary, R.drawable.ic_symbols_folder_24) {
            startActivity(Intent(this, GroupListActivity::class.java))
        },
        HubEntry(R.string.import_data, R.string.settings_section_import_summary, R.drawable.ic_symbols_download_24) {
            actions.importBackup()
        },
        HubEntry(R.string.export_data, R.string.settings_section_export_summary, R.drawable.ic_symbols_upload_24) {
            actions.exportBackup()
        },
        HubEntry(R.string.clear_data, R.string.settings_section_clear_summary, R.drawable.ic_symbols_delete_24) {
            actions.clearData()
        },
        HubEntry(R.string.app_info, R.string.settings_section_about_summary, R.drawable.ic_symbols_info_24) {
            actions.showAbout()
        },
    )

    private data class HubEntry(
        @param:StringRes val title: Int,
        @param:StringRes val summary: Int,
        @param:DrawableRes val icon: Int,
        val onClick: () -> Unit,
    )

    private class HubAdapter(
        private val entries: List<HubEntry>,
        private val onClick: (HubEntry) -> Unit,
    ) : RecyclerView.Adapter<HubAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSettingsHubBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                ItemSettingsHubBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false,
                ),
            )

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.binding.hubIcon.setImageResource(entry.icon)
            holder.binding.hubTitle.setText(entry.title)
            holder.binding.hubSubtitle.setText(entry.summary)
            holder.binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
