package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GlobalSettingsBinding
import wtf.mazy.peel.model.ApplyTiming
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.ui.settings.SettingViewFactory
import kotlin.system.exitProcess

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    private lateinit var editableSettings: wtf.mazy.peel.model.WebApp
    private var currentSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.global_settings))
        editableSettings = DataManager.instance.defaultSettings
        setupDefaultSettingsUI()
        setupKeyboardPadding(binding.scrollView)
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.instance.setDefaultSettings(editableSettings)
            }
        }
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }

    private fun setupDefaultSettingsUI() {
        val settings = editableSettings.settings
        val container = binding.linearLayoutGlobalSettings
        val factory = SettingViewFactory(
            layoutInflater,
            SettingViewFactory.ButtonStrategy.GlobalDefaults(),
            ::onSettingChanged,
        )

        val settingsGrouped =
            SettingRegistry.getAllSettings()
                .groupBy { it.category }
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
        val headerView =
            layoutInflater.inflate(R.layout.item_setting_category_header, container, false)
                    as TextView
        headerView.text = title
        container.addView(headerView)
    }

    private fun addDivider(container: LinearLayout) {
        val divider =
            View(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.divider_height),
                    )
                        .apply {
                            topMargin =
                                resources.getDimensionPixelSize(R.dimen.settings_divider_margin_top)
                            bottomMargin =
                                resources.getDimensionPixelSize(
                                    R.dimen.settings_divider_margin_bottom
                                )
                        }
                val attrs = intArrayOf(android.R.attr.listDivider)
                withStyledAttributes(null, attrs) { background = getDrawable(0) }
            }
        container.addView(divider)
    }

    private fun onSettingChanged(key: String) {
        val timing = ApplyTimingRegistry.getTiming(key)
        if (timing == ApplyTiming.IMMEDIATE) return
        currentSnackbar?.dismiss()
        val message = when (timing) {
            ApplyTiming.PEEL_RESTART -> R.string.setting_requires_peel_restart
            ApplyTiming.WEBAPP_RESTART -> R.string.setting_requires_webapp_restart
            else -> return
        }
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        snackbar.setAction(R.string.restart) { restartApp() }
        currentSnackbar = snackbar
        snackbar.show()
    }

    private fun restartApp() {
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.instance.setDefaultSettings(editableSettings)
            }
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            intent?.let { startActivity(it) }
            Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 200)
        }
    }
}
