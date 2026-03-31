package wtf.mazy.peel.ui.settings

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.SandboxOwner
import wtf.mazy.peel.util.NotificationUtils.showToast

class SandboxSwitchController(
    private val activity: AppCompatActivity,
    private val owner: SandboxOwner,
    private val switchSandbox: MaterialSwitch,
    private val switchEphemeral: MaterialSwitch,
    private val ephemeralRow: View,
    private val btnClear: View,
) {
    fun setup() {
        updateEphemeralVisibility()
        updateClearButtonVisibility()

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
                        owner.isEphemeralSandbox = true
                        clearSandboxData()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        switchEphemeral.isChecked = false
                    }
                    .show()
            } else {
                owner.isEphemeralSandbox = false
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
        owner.isUseContainer = true
        updateEphemeralVisibility()
        updateClearButtonVisibility()
        return
    }

    private fun disableSandbox() {
        owner.isUseContainer = false
        owner.isEphemeralSandbox = false
        switchEphemeral.isChecked = false
        updateEphemeralVisibility()
        updateClearButtonVisibility()
    }

    private fun clearSandboxData() {
        BrowserActivity.finishByUuid(owner.uuid)
        if (SandboxManager.clearSandboxData(activity, owner.uuid)) {
            showToast(activity, activity.getString(R.string.clear_sandbox_data), Toast.LENGTH_SHORT)
        }
        updateClearButtonVisibility()
    }
}
