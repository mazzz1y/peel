package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GlobalSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.ui.settings.SettingViewFactory

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.global_settings))
        setupDefaultSettingsUI()
        setupKeyboardPadding(binding.linearLayoutGlobalSettings)
    }

    override fun onPause() {
        super.onPause()
        DataManager.instance.saveDefaultSettings()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }

    private fun setupDefaultSettingsUI() {
        val settings = DataManager.instance.defaultSettings.settings
        val container = binding.linearLayoutGlobalSettings
        val factory =
            SettingViewFactory(layoutInflater, SettingViewFactory.ButtonStrategy.GlobalDefaults())

        val settingsGrouped = SettingRegistry.getAllSettings().groupBy { it.category }
            .toSortedMap(compareBy { it.ordinal })

        val categories = settingsGrouped.keys.toList()
        settingsGrouped.forEach { (category, definitions) ->
            addCategoryHeader(container, getString(category.displayNameResId))

            definitions.forEach { definition ->
                container.addView(factory.createView(container, definition, settings))
            }

            if (category != categories.last()) {
                addDivider(container)
            }
        }
    }

    private fun addCategoryHeader(container: LinearLayout, title: String) {
        val headerView = layoutInflater.inflate(
            R.layout.item_setting_category_header,
            container,
            false
        ) as TextView
        headerView.text = title
        container.addView(headerView)
    }

    private fun addDivider(container: LinearLayout) {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.divider_height),
            ).apply {
                    topMargin = resources.getDimensionPixelSize(
                        R.dimen.settings_divider_margin_top
                    )
                    bottomMargin = resources.getDimensionPixelSize(
                        R.dimen.settings_divider_margin_bottom
                    )
                }
            val attrs = intArrayOf(android.R.attr.listDivider)
            withStyledAttributes(null, attrs) { background = getDrawable(0) }
        }
        container.addView(divider)
    }
}
