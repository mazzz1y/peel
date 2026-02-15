package wtf.mazy.peel.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GroupSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.SettingRegistry
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.shortcut.LetterIconGenerator
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.settings.SettingViewFactory
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast

class GroupSettingsActivity :
    ToolbarBaseActivity<GroupSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {

    private var originalGroup: WebAppGroup? = null
    private var modifiedGroup: WebAppGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.group_settings))

        val groupUuid = intent.getStringExtra(Const.INTENT_GROUP_UUID)
        originalGroup = groupUuid?.let { DataManager.instance.getGroup(it) }

        if (originalGroup == null) {
            showToast(this, getString(R.string.webapp_not_found), Toast.LENGTH_SHORT)
            finish()
            return
        }

        modifiedGroup = WebAppGroup(originalGroup!!)
        binding.group = modifiedGroup

        updateGroupIcon()
        binding.txtGroupName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateGroupIcon() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        setupSandboxSwitch()
        setupOverridePicker()
        setupKeyboardListener()
    }

    override fun onPause() {
        super.onPause()
        modifiedGroup?.let {
            if (it.title.isBlank()) {
                it.title = originalGroup?.title ?: ""
            }
            DataManager.instance.replaceGroup(it)
        }
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GroupSettingsBinding {
        return GroupSettingsBinding.inflate(layoutInflater)
    }

    private fun updateGroupIcon() {
        val group = modifiedGroup ?: return
        val sizePx = (resources.displayMetrics.density * 96).toInt()
        binding.imgGroupIcon.setImageBitmap(
            LetterIconGenerator.generate(group.title, group.title, sizePx)
        )
    }

    private fun setupSandboxSwitch() {
        val group = modifiedGroup ?: return
        updateEphemeralVisibility(group.isUseContainer)
        updateClearSandboxButtonVisibility(group)

        binding.switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == group.isUseContainer) return@setOnCheckedChangeListener
            group.isUseContainer = isChecked
            if (!isChecked) {
                group.isEphemeralSandbox = false
                binding.switchEphemeralSandbox.isChecked = false
            }
            updateEphemeralVisibility(isChecked)
            updateClearSandboxButtonVisibility(group)
        }

        binding.switchEphemeralSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == group.isEphemeralSandbox) return@setOnCheckedChangeListener
            group.isEphemeralSandbox = isChecked
            updateClearSandboxButtonVisibility(group)
        }

        binding.btnClearSandbox.setOnClickListener { showClearSandboxConfirmDialog(group) }
    }

    private fun updateEphemeralVisibility(sandboxEnabled: Boolean) {
        binding.ephemeralSandboxRow.visibility = if (sandboxEnabled) View.VISIBLE else View.GONE
    }

    private fun updateClearSandboxButtonVisibility(group: WebAppGroup) {
        val sandboxDir = SandboxManager.getSandboxDataDir(group.uuid)
        val show = sandboxDir.exists() && group.isUseContainer && !group.isEphemeralSandbox
        binding.btnClearSandbox.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showClearSandboxConfirmDialog(group: WebAppGroup) {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_group_sandbox_data_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> clearSandboxData(group) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearSandboxData(group: WebAppGroup) {
        if (SandboxManager.clearSandboxData(this, group.uuid)) {
            showToast(this, getString(R.string.clear_sandbox_data), Toast.LENGTH_SHORT)
        }
        updateClearSandboxButtonVisibility(group)
    }

    private lateinit var overrideViewFactory: SettingViewFactory

    private fun setupOverridePicker() {
        val group = modifiedGroup ?: return
        updateOverrideViewFactory(group)
        updateOverridesList(group)
        binding.btnAddOverride.setOnClickListener { showOverridePickerDialog(group) }
    }

    private fun updateOverrideViewFactory(group: WebAppGroup) {
        overrideViewFactory =
            SettingViewFactory(
                layoutInflater,
                SettingViewFactory.ButtonStrategy.Override { setting ->
                    removeOverride(group, setting.key)
                    updateOverridesList(group)
                },
            )
    }

    private fun updateOverridesList(group: WebAppGroup) {
        val allOverriddenKeys = group.settings.getOverriddenKeys()
        val compoundKeys =
            SettingRegistry.getAllSettings().flatMapTo(mutableSetOf()) { setting ->
                setting.allFields.drop(1).map { it.key }
            }
        val overriddenKeys = allOverriddenKeys.filter { it !in compoundKeys }

        val container = binding.linearLayoutOverrides
        container.removeAllViews()

        overriddenKeys.forEach { key ->
            val setting = SettingRegistry.getSettingByKey(key) ?: return@forEach
            container.addView(
                overrideViewFactory.createView(container, setting, group.settings))
        }
    }

    private fun removeOverride(group: WebAppGroup, key: String) {
        val setting = SettingRegistry.getSettingByKey(key) ?: return
        setting.allFields.forEach { group.settings.setValue(it.key, null) }
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        val group = modifiedGroup ?: return
        val globalSettings = DataManager.instance.defaultSettings.settings
        if (setting is SettingDefinition.StringMapSetting) {
            group.settings.customHeaders = mutableMapOf()
        } else {
            setting.allFields.forEach { field ->
                group.settings.setValue(field.key, globalSettings.getValue(field.key))
            }
        }
        binding.linearLayoutOverrides.addView(
            overrideViewFactory.createView(binding.linearLayoutOverrides, setting, group.settings))
    }

    private fun showOverridePickerDialog(group: WebAppGroup) {
        val dialog =
            OverridePickerDialog.newInstance(
                group.settings,
                DataManager.instance.defaultSettings.settings,
                this,
            )
        dialog.show(supportFragmentManager, "OverridePickerDialog")
    }

    private fun setupKeyboardListener() {
        val rootView = binding.root
        val contentContainer = binding.contentContainer

        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - rect.bottom

            if (keyboardHeight > screenHeight * 0.15) {
                contentContainer.setPadding(0, 0, 0, keyboardHeight)
            } else {
                contentContainer.setPadding(0, 0, 0, 0)
            }
        }
    }
}
