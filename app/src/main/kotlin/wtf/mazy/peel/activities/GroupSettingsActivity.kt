package wtf.mazy.peel.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.mazy.peel.R
import wtf.mazy.peel.databinding.GroupSettingsBinding
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SettingDefinition
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.WebAppSettings
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
    private lateinit var originalSettingsSnapshot: WebAppSettings

    private val imgGroupIcon get() = binding.root.findViewById<ImageView>(R.id.imgGroupIcon)
    private val txtGroupName get() = binding.root.findViewById<TextView>(R.id.txtGroupName)
    private val titleBlock get() = binding.root.findViewById<View>(R.id.titleBlock)
    private val sandboxLabel get() = binding.root.findViewById<TextView>(R.id.sandboxLabel)
    private val switchSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchSandbox)
    private val switchEphemeralSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchEphemeralSandbox)
    private val ephemeralSandboxRow get() = binding.root.findViewById<LinearLayout>(R.id.ephemeralSandboxRow)
    private val btnClearSandbox get() = binding.root.findViewById<ImageButton>(R.id.btnClearSandbox)
    private val btnAddOverride get() = binding.root.findViewById<ImageButton>(R.id.btnAddOverride)
    private val linearLayoutOverrides get() = binding.root.findViewById<LinearLayout>(R.id.linearLayoutOverrides)

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { imgGroupIcon }) { modifiedGroup }
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
        originalSettingsSnapshot = originalGroup!!.settings.deepCopy()
        txtGroupName.text = modifiedGroup?.title
        sandboxLabel.setText(R.string.group_sandbox)
        switchSandbox.isChecked = modifiedGroup?.isUseContainer == true
        switchEphemeralSandbox.isChecked = modifiedGroup?.isEphemeralSandbox == true

        imgGroupIcon.setOnClickListener { iconEditor.onIconTap() }
        titleBlock.setOnClickListener { modifiedGroup?.let { showEditDialog(it) } }
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
            it.settings.sanitize(asOverride = true)
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    DataManager.instance.replaceGroup(it)
                }
            }
        }
    }

    override fun finish() {
        modifiedGroup?.let {
            val changed = ApplyTimingRegistry.getChangedKeys(originalSettingsSnapshot, it.settings)
            val timing = ApplyTimingRegistry.getHighestTiming(changed)
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name)
            )
        }
        super.finish()
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
            txtGroupName.text = name
            iconEditor.refreshIcon()
        }
    }

    private fun setupSandboxSwitch() {
        val group = modifiedGroup ?: return
        SandboxSwitchController(
            this,
            group,
            switchSandbox,
            switchEphemeralSandbox,
            ephemeralSandboxRow,
            btnClearSandbox,
        ).setup()
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker() {
        val group = modifiedGroup ?: return
        overrideController =
            OverridePickerController(
                this,
                group.settings,
                linearLayoutOverrides,
                btnAddOverride,
            )
        overrideController.setup()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
