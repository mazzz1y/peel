package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GroupSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.IconEditorController
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.dialog.showInputDialog
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.NotificationUtils.showToast

class GroupSettingsActivity :
    ToolbarBaseActivity<GroupSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {

    private var originalGroup: WebAppGroup? = null
    private var modifiedGroup: WebAppGroup? = null
    private lateinit var iconEditor: IconEditorController
    private var currentSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { binding.imgGroupIcon }) { modifiedGroup }
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.group_settings))

        val groupUuid = intent.getStringExtra(Const.INTENT_GROUP_UUID)
        originalGroup = groupUuid?.let { DataManager.instance.getGroup(it) }

        if (originalGroup == null) {
            showToast(this, getString(R.string.group_not_found), Toast.LENGTH_SHORT)
            finish()
            return
        }

        modifiedGroup = WebAppGroup(originalGroup!!)
        binding.group = modifiedGroup

        binding.imgGroupIcon.setOnClickListener { iconEditor.onIconTap() }
        binding.titleBlock.setOnClickListener { modifiedGroup?.let { showEditDialog(it) } }
        iconEditor.refreshIcon()

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
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    DataManager.instance.replaceGroup(it)
                }
            }
        }
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GroupSettingsBinding {
        return GroupSettingsBinding.inflate(layoutInflater)
    }

    private fun showEditDialog(group: WebAppGroup) {
        showInputDialog(
            InputDialogConfig(
                hintRes = R.string.name,
                prefill = group.title,
                positiveRes = R.string.save,
            ),
        ) { name ->
            group.title = name
            binding.txtGroupName.text = name
            iconEditor.refreshIcon()
        }
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
        ).setup()
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
                ::onSettingChanged,
            )
        overrideController.setup()
    }

    private fun onSettingChanged(key: String) {
        currentSnackbar = ApplyTimingRegistry.showSnackbarIfNeeded(key, binding.root, currentSnackbar)
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
