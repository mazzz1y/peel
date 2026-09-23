package wtf.mazy.peel.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import wtf.mazy.peel.ui.common.Draft
import wtf.mazy.peel.ui.common.GroupPosition
import wtf.mazy.peel.ui.common.SettingsSurface
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.OverridePickerDialog
import wtf.mazy.peel.ui.dialog.showInputDialog
import wtf.mazy.peel.ui.settings.OverridePickerController
import wtf.mazy.peel.ui.settings.ProxyDropdownController
import wtf.mazy.peel.ui.settings.SandboxSwitchController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.toast

class GroupSettingsActivity :
    ToolbarBaseActivity<GroupSettingsBinding>(), OverridePickerDialog.OnSettingSelectedListener {

    private var originalTitle: String = ""
    private var draft: Draft<WebAppGroup>? = null
    private lateinit var iconEditor: IconEditorController
    private lateinit var originalSettingsSnapshot: WebAppSettings

    private val imgGroupIcon get() = binding.root.findViewById<ImageView>(R.id.imgGroupIcon)
    private val txtGroupName get() = binding.root.findViewById<TextView>(R.id.txtGroupName)
    private val titleBlock get() = binding.root.findViewById<View>(R.id.titleBlock)
    private val sandboxLabel get() = binding.root.findViewById<TextView>(R.id.sandboxLabel)
    private val switchSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchSandbox)
    private val switchEphemeralSandbox get() = binding.root.findViewById<MaterialSwitch>(R.id.switchEphemeralSandbox)
    private val ephemeralSandboxRow get() = binding.root.findViewById<LinearLayout>(R.id.ephemeralSandboxRow)
    private val proxyRow get() = binding.root.findViewById<LinearLayout>(R.id.proxyRow)
    private val btnProxyPicker get() = binding.root.findViewById<MaterialButton>(R.id.btnProxyPicker)
    private val btnClearSandbox get() = binding.root.findViewById<MaterialButton>(R.id.btnClearSandbox)
    private val btnAddOverride get() = binding.root.findViewById<MaterialButton>(R.id.btnAddOverride)
    private val linearLayoutOverrides get() = binding.root.findViewById<LinearLayout>(R.id.linearLayoutOverrides)

    override fun onCreate(savedInstanceState: Bundle?) {
        iconEditor = IconEditorController(this, { imgGroupIcon }) { draft?.value }
        super.onCreate(savedInstanceState)
        setToolbarTitle(getString(R.string.group_settings))

        val stored = intent.getStringExtra(Const.INTENT_GROUP_UUID)
            ?.let { DataManager.group(it) }
        if (stored == null) {
            toast(R.string.group_not_found)
            finish()
            return
        }

        val draft = Draft(stored.copy(settings = stored.settings.deepCopy()))
        this.draft = draft
        originalTitle = stored.title
        originalSettingsSnapshot =
            stored.settings.deepCopy().apply { sanitize(asOverride = true) }
        txtGroupName.text = stored.title
        sandboxLabel.setText(R.string.group_sandbox)
        switchSandbox.isChecked = stored.isUseContainer
        switchEphemeralSandbox.isChecked = stored.isEphemeralSandbox

        imgGroupIcon.setOnClickListener { iconEditor.onIconTap() }
        titleBlock.setOnClickListener { showEditDialog(draft) }
        iconEditor.refreshIcon()

        setupSandboxSwitch()
        SettingsSurface.apply(binding.root.findViewById(R.id.identityBlock), GroupPosition.ONLY)
        SettingsSurface.bindGroup(binding.settingsRowsGroup)
        setupOverridePicker()
        setupScrollInsets(binding.scrollView)
    }

    override fun onPause() {
        super.onPause()
        val draft = draft ?: return
        if (draft.value.title.isBlank()) draft.update { it.copy(title = originalTitle) }
        val group = draft.value
        group.settings.sanitize(asOverride = true)
        lifecycleScope.launch {
            withContext(NonCancellable) {
                DataManager.replaceGroup(group)
            }
        }
    }

    override fun finish() {
        draft?.value?.let {
            it.settings.sanitize(asOverride = true)
            val changed = ApplyTimingRegistry.changedKeys(originalSettingsSnapshot, it.settings)
            val timing = ApplyTimingRegistry.highestTiming(changed)
            setResult(
                RESULT_OK,
                Intent().putExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING, timing.name)
            )
        }
        super.finish()
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GroupSettingsBinding {
        return GroupSettingsBinding.inflate(layoutInflater)
    }

    private fun showEditDialog(draft: Draft<WebAppGroup>) {
        showInputDialog(
            InputDialogConfig(
                hintRes = R.string.name,
                prefill = draft.value.title,
                positiveRes = R.string.save,
            ),
        ) { name ->
            draft.update { it.copy(title = name) }
            txtGroupName.text = name
            iconEditor.refreshIcon()
        }
    }

    private fun setupSandboxSwitch() {
        val draft = draft ?: return
        val proxyController = ProxyDropdownController(
            activity = this,
            draft = draft,
            proxyRow = proxyRow,
            proxyButton = btnProxyPicker,
        )
        SandboxSwitchController(
            this,
            draft,
            switchSandbox,
            switchEphemeralSandbox,
            ephemeralSandboxRow,
            btnClearSandbox,
            onSandboxChanged = { proxyController.refresh() },
        ).setup()
        proxyController.setup()
    }

    private lateinit var overrideController: OverridePickerController

    private fun setupOverridePicker() {
        val settings = draft?.value?.settings ?: return
        overrideController =
            OverridePickerController(
                this,
                settings,
                linearLayoutOverrides,
                btnAddOverride,
            )
        overrideController.setup()
    }

    override fun onSettingSelected(setting: SettingDefinition) {
        overrideController.onSettingSelected(setting)
    }
}
