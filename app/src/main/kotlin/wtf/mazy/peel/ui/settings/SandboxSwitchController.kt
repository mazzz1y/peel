package wtf.mazy.peel.ui.settings

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import wtf.mazy.peel.R
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
    private val onReleaseSandbox: (() -> Unit)? = null,
    private val memberUuids: (() -> List<String>)? = null,
) {
    fun setup() {
        updateEphemeralVisibility()
        updateClearButtonVisibility()

        switchSandbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == owner.isUseContainer) return@setOnCheckedChangeListener
            onReleaseSandbox?.invoke()
            owner.isUseContainer = isChecked
            if (!isChecked) {
                owner.isEphemeralSandbox = false
                switchEphemeral.isChecked = false
            }
            updateEphemeralVisibility()
            updateClearButtonVisibility()
        }

        switchEphemeral.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == owner.isEphemeralSandbox) return@setOnCheckedChangeListener
            if (isChecked) {
                val sandboxDir = SandboxManager.getSandboxDataDir(owner.uuid)
                if (sandboxDir.exists()) {
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
                    onReleaseSandbox?.invoke()
                    owner.isEphemeralSandbox = true
                    updateClearButtonVisibility()
                }
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
        val sandboxDir = SandboxManager.getSandboxDataDir(owner.uuid)
        val show = sandboxDir.exists() && owner.isUseContainer && !owner.isEphemeralSandbox
        btnClear.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showClearConfirmDialog() {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.clear_sandbox_data_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> clearSandboxData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearSandboxData() {
        val extras = memberUuids?.invoke() ?: emptyList()
        if (SandboxManager.clearSandboxData(activity, owner.uuid, extras)) {
            showToast(activity, activity.getString(R.string.clear_sandbox_data), Toast.LENGTH_SHORT)
        }
        updateClearButtonVisibility()
    }
}
