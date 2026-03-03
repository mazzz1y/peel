package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R

data class SandboxInputResult(
    val text: String,
    val sandbox: Boolean,
    val ephemeral: Boolean,
)

fun Activity.showSandboxInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    onResult: (SandboxInputResult) -> Unit,
) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_add_with_sandbox, null)
    val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.inputLayout)
    val input = dialogView.findViewById<TextInputEditText>(R.id.input)
    val switchSandbox = dialogView.findViewById<MaterialSwitch>(R.id.switchSandbox)
    val switchEphemeral = dialogView.findViewById<MaterialSwitch>(R.id.switchEphemeral)

    inputLayout.hint = getString(hintRes)
    input.inputType = inputType

    switchSandbox.setOnCheckedChangeListener { _, isChecked ->
        switchEphemeral.visibility = if (isChecked) View.VISIBLE else View.GONE
        if (!isChecked) switchEphemeral.isChecked = false
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(titleRes)
        .setView(dialogView)
        .setPositiveButton(R.string.ok) { _, _ ->
            val text = input.text?.toString()?.trim() ?: return@setPositiveButton
            if (text.isBlank()) return@setPositiveButton
            onResult(SandboxInputResult(text, switchSandbox.isChecked, switchEphemeral.isChecked))
        }
        .setNegativeButton(R.string.cancel, null)
        .create()

    dialog.show()
    val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    okButton.isEnabled = false
    input.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            okButton.isEnabled = !s.isNullOrBlank()
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}
