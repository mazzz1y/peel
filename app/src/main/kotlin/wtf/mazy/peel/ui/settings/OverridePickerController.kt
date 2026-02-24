package wtf.mazy.peel.ui.settings

import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.ui.dialog.OverridePickerDialog

class OverridePickerController(
    private val activity: AppCompatActivity,
    private val settings: WebAppSettings,
    private val container: LinearLayout,
    private val addButton: View,
) : OverridePickerDialog.OnSettingSelectedListener {

    private var viewFactory = createViewFactory()

    fun setup() {
        refreshList()
        addButton.setOnClickListener { showPickerDialog() }
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        val globalSettings = DataManager.instance.defaultSettings.settings
        if (setting is SettingDefinition.StringMapSetting) {
            settings.customHeaders = mutableMapOf()
        } else {
            setting.allFields.forEach { field ->
                settings.setValue(field.key, globalSettings.getValue(field.key))
            }
        }
        container.addView(viewFactory.createView(container, setting, settings))
    }

    private fun createViewFactory(): SettingViewFactory =
        SettingViewFactory(
            activity.layoutInflater,
            SettingViewFactory.ButtonStrategy.Override { setting ->
                removeOverride(setting.key)
                refreshList()
            },
        )

    private fun refreshList() {
        val allOverriddenKeys = settings.getOverriddenKeys()
        val compoundKeys =
            SettingRegistry.getAllSettings().flatMapTo(mutableSetOf()) { setting ->
                setting.allFields.drop(1).map { it.key }
            }
        val overriddenKeys = allOverriddenKeys.filter { it !in compoundKeys }

        container.removeAllViews()
        overriddenKeys.forEach { key ->
            val setting = SettingRegistry.getSettingByKey(key) ?: return@forEach
            container.addView(viewFactory.createView(container, setting, settings))
        }
    }

    private fun removeOverride(key: String) {
        val setting = SettingRegistry.getSettingByKey(key) ?: return
        setting.allFields.forEach { settings.setValue(it.key, null) }
    }

    private fun showPickerDialog() {
        val dialog = OverridePickerDialog.newInstance(
            settings,
            DataManager.instance.defaultSettings.settings,
            this,
        )
        dialog.show(activity.supportFragmentManager, "OverridePickerDialog")
    }
}
