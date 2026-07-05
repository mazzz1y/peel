package wtf.mazy.peel.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GlobalSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.SettingSection
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.settings.SettingViewFactory
import wtf.mazy.peel.ui.settings.SettingsAdapter
import wtf.mazy.peel.ui.settings.SettingsListItem

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    private lateinit var editableSettings: WebApp
    private lateinit var originalSnapshot: WebAppSettings
    private lateinit var section: SettingSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra(EXTRA_SECTION)
            ?.let { runCatching { SettingSection.valueOf(it) }.getOrNull() }
            ?: SettingSection.GLOBAL
        setToolbarTitle(getString(section.displayNameResId))
        editableSettings = DataManager.instance.defaultSettings
        originalSnapshot = editableSettings.settings.deepCopy().apply { sanitize() }
        setupDefaultSettingsUI()
    }

    override fun onPause() {
        super.onPause()
        editableSettings.settings.sanitize()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.instance.setDefaultSettings(editableSettings)
            }
        }
    }

    override fun finish() {
        editableSettings.settings.sanitize()
        val changed =
            ApplyTimingRegistry.getChangedKeys(originalSnapshot, editableSettings.settings)
        val timing = ApplyTimingRegistry.getHighestTiming(changed)
        setResult(
            RESULT_OK,
            Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name)
        )
        super.finish()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }

    private fun setupDefaultSettingsUI() {
        val settings = editableSettings.settings
        val factory = SettingViewFactory(
            layoutInflater,
            SettingViewFactory.ButtonStrategy.GlobalDefaults,
            lifecycleScope,
        )

        val settingsGrouped =
            SettingRegistry.getSettingsForSection(section)
                .groupBy { it.category }
                .toSortedMap(compareBy { it.ordinal })

        val items = buildList {
            settingsGrouped.forEach { (category, definitions) ->
                if (isNotEmpty()) add(SettingsListItem.Divider)
                add(SettingsListItem.Header(category))
                definitions.forEach { add(SettingsListItem.Setting(it)) }
            }
        }

        binding.recyclerSettings.layoutManager = LinearLayoutManager(this)
        binding.recyclerSettings.adapter = SettingsAdapter(items, settings, factory)
        setupKeyboardPadding(binding.recyclerSettings)
    }

    companion object {
        const val EXTRA_SECTION = "section"

        fun intentForSection(context: Context, section: SettingSection): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_SECTION, section.name)
    }
}
