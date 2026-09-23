package wtf.mazy.peel.ui.settings

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.gecko.SandboxManager
import wtf.mazy.peel.model.SandboxOwner
import wtf.mazy.peel.ui.common.Draft
import wtf.mazy.peel.util.toast

class SandboxSwitchController<T : SandboxOwner<T>>(
    private val activity: AppCompatActivity,
    private val draft: Draft<T>,
    private val switchSandbox: MaterialSwitch,
    private val switchEphemeral: MaterialSwitch,
    private val ephemeralRow: View,
    private val btnClear: View,
    private val onSandboxChanged: (() -> Unit)? = null,
) {
    private val owner: T
        get() = draft.value

    fun setup() {
        updateEphemeralVisibility()
        updateClearButtonVisibility()
        onSandboxChanged?.invoke()

        switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == owner.isUseContainer) return@setOnCheckedChangeListener
            if (!isChecked) {
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.clear_sandbox_data_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        disableSandbox()
                        clearSandboxData()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        switchSandbox.isChecked = true
                    }
                    .show()
            } else {
                setSandboxEnabled()
            }
        }

        switchEphemeral.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == owner.isEphemeralSandbox) return@setOnCheckedChangeListener
            if (isChecked) {
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.clear_sandbox_data_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        draft.update { it.withSandbox(isEphemeralSandbox = true) }
                        clearSandboxData()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        switchEphemeral.isChecked = false
                    }
                    .show()
            } else {
                draft.update { it.withSandbox(isEphemeralSandbox = false) }
                updateClearButtonVisibility()
            }
        }

        btnClear.setOnClickListener { showClearConfirmDialog() }
    }

    private fun updateEphemeralVisibility() {
        ephemeralRow.visibility = if (owner.isUseContainer) View.VISIBLE else View.GONE
    }

    private fun updateClearButtonVisibility() {
        val show = owner.isUseContainer && !owner.isEphemeralSandbox
        btnClear.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showClearConfirmDialog() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.clear_sandbox_data_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> clearSandboxData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setSandboxEnabled() {
        draft.update { it.withSandbox(isUseContainer = true) }
        updateEphemeralVisibility()
        updateClearButtonVisibility()
        onSandboxChanged?.invoke()
    }

    private fun disableSandbox() {
        draft.update {
            it.withSandbox(isUseContainer = false, isEphemeralSandbox = false, proxyUuid = null)
        }
        switchEphemeral.isChecked = false
        updateEphemeralVisibility()
        updateClearButtonVisibility()
        onSandboxChanged?.invoke()
    }

    private fun clearSandboxData() {
        activity.lifecycleScope.launch {
            if (SandboxManager.clearSandboxData(activity, owner.uuid)) {
                activity.toast(R.string.clear_sandbox_data)
            }
            updateClearButtonVisibility()
        }
    }
}
