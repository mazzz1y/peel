package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R

object BackupPasswordDialog {

    private const val PASSWORD_INPUT_TYPE =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    fun requestNew(activity: Activity, onResult: (CharArray) -> Unit, onCancel: () -> Unit) {
        var passwordInput: TextInputEditText? = null
        var repeatLayout: TextInputLayout? = null
        var repeatInput: TextInputEditText? = null
        val dp8 = (activity.resources.displayMetrics.density * 8).toInt()

        val dialog = activity.showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.backup_password_title,
                hintRes = R.string.password,
                message = activity.getString(R.string.backup_password_warning),
                inputType = PASSWORD_INPUT_TYPE,
                // Confirmation owns the button state instead of the default blank rule.
                allowEmpty = true,
                onCancel = {
                    passwordInput?.text?.clear()
                    repeatInput?.text?.clear()
                    onCancel()
                },
                onInputReady = {
                    passwordInput = it
                    it.enablePasswordToggle()
                },
                extraContent = { container ->
                    val layout = TextInputLayout(container.context).apply {
                        hint = activity.getString(R.string.backup_password_repeat)
                        endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp8 }
                    }
                    val repeat = TextInputEditText(layout.context).apply {
                        inputType = PASSWORD_INPUT_TYPE
                        isSingleLine = true
                    }
                    layout.addView(repeat)
                    container.addView(layout)
                    repeatLayout = layout
                    repeatInput = repeat
                },
            ),
        ) { input, _ ->
            onResult(input.readPassword())
            input.text?.clear()
            repeatInput?.text?.clear()
        }

        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = false

        fun revalidate() {
            val first = passwordInput?.text
            val second = repeatInput?.text
            val matches = !first.isNullOrEmpty() && first.contentEqualsExactly(second)
            okButton.isEnabled = matches
            repeatLayout?.error =
                if (!second.isNullOrEmpty() && !matches) {
                    activity.getString(R.string.backup_password_mismatch)
                } else {
                    null
                }
        }

        passwordInput?.doAfterTextChanged { revalidate() }
        repeatInput?.doAfterTextChanged { revalidate() }
    }

    fun requestExisting(activity: Activity, onResult: (CharArray) -> Unit, onCancel: () -> Unit) {
        // The default rule rejects blanks, which would lock out a whitespace-only
        // password that requestNew accepted.
        var passwordInput: TextInputEditText? = null
        val dialog = activity.showInputDialogRaw(
            InputDialogConfig(
                titleRes = R.string.backup_password_prompt_title,
                hintRes = R.string.password,
                inputType = PASSWORD_INPUT_TYPE,
                allowEmpty = true,
                onCancel = {
                    passwordInput?.text?.clear()
                    onCancel()
                },
                onInputReady = {
                    passwordInput = it
                    it.enablePasswordToggle()
                },
            ),
        ) { input, _ ->
            onResult(input.readPassword())
            input.text?.clear()
        }

        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = false
        passwordInput?.doAfterTextChanged { okButton.isEnabled = !it.isNullOrEmpty() }
    }

    private fun TextInputEditText.enablePasswordToggle() {
        (parent?.parent as? TextInputLayout)?.endIconMode =
            TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }

    private fun TextInputEditText.readPassword(): CharArray {
        val editable = text ?: return CharArray(0)
        return CharArray(editable.length) { editable[it] }
    }

    private fun CharSequence.contentEqualsExactly(other: CharSequence?): Boolean =
        other != null && length == other.length && indices.all { this[it] == other[it] }
}
