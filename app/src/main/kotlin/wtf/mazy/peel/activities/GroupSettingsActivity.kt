package wtf.mazy.peel.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Toast
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GroupSettingsBinding
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast

class GroupSettingsActivity :
    ToolbarBaseActivity<GroupSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {

    private var originalGroup: WebAppGroup? = null
    private var modifiedGroup: WebAppGroup? = null
    private lateinit var iconEditor: IconEditorController

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { binding.imgGroupIcon }) { modifiedGroup }
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

        binding.imgGroupIcon.setOnClickListener { iconEditor.onIconTap() }
        iconEditor.refreshIcon()
        binding.txtGroupName.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    iconEditor.refreshIcon()
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

        setupSandboxSwitch()
        setupOverridePicker()
        setupKeyboardPadding(binding.scrollView)
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

    private fun setupSandboxSwitch() {
        val group = modifiedGroup ?: return
        SandboxSwitchController(
            this,
            group,
            binding.switchSandbox,
            binding.switchEphemeralSandbox,
            binding.ephemeralSandboxRow,
            binding.btnClearSandbox,
            memberUuids = {
                DataManager.instance.activeWebsitesForGroup(group.uuid).map { it.uuid }
            },
        )
            .setup()
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker() {
        val group = modifiedGroup ?: return
        overrideController =
            OverridePickerController(
                this,
                group.settings,
                binding.linearLayoutOverrides,
                binding.btnAddOverride,
            )
        overrideController.setup()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
