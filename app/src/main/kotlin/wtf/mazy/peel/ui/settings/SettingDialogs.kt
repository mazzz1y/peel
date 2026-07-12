package wtf.mazy.peel.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.R

object SettingDialogs {

    data class Credentials(val username: String, val password: String)

    private fun contentPadding(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.dialog_content_horizontal_padding)

    private fun buildWrapper(context: Context): LinearLayout {
        val padding = contentPadding(context)
        val top = context.resources.getDimensionPixelSize(R.dimen.settings_item_gap)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, top, padding, 0)
        }
    }

    private fun outlinedField(
        context: Context,
        hintRes: Int,
        endIconPasswordToggle: Boolean = false,
        topMargin: Int = 0,
    ): TextInputLayout =
        TextInputLayout(context).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = context.getString(hintRes)
            if (endIconPasswordToggle) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { this.topMargin = topMargin }
        }

    private fun fieldGap(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.settings_item_gap)

    private fun editIn(
        layout: TextInputLayout,
        prefill: String,
        inputType: Int,
        maxLines: Int,
        autofillHint: String? = null,
    ): TextInputEditText =
        TextInputEditText(layout.context).apply {
            setText(prefill)
            setInputType(inputType)
            setMaxLines(maxLines)
            isSingleLine = maxLines <= 1
            setSelection(text?.length ?: 0)
            if (autofillHint != null) setAutofillHints(autofillHint)
            layout.addView(this)
        }

    fun showCredentials(
        context: Context,
        titleRes: Int,
        username: String,
        password: String,
        onCancel: () -> Unit = {},
        onCommit: (Credentials) -> Unit,
    ) {
        val wrapper = buildWrapper(context)
        val usernameLayout = outlinedField(context, R.string.username)
        val usernameEdit = editIn(
            usernameLayout,
            username,
            InputType.TYPE_CLASS_TEXT,
            maxLines = 1,
            autofillHint = "username",
        )
        val passwordLayout = outlinedField(
            context,
            R.string.password,
            endIconPasswordToggle = true,
            topMargin = fieldGap(context),
        )
        val passwordEdit = editIn(
            passwordLayout,
            password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            maxLines = 1,
            autofillHint = "password",
        )
        wrapper.addView(usernameLayout)
        wrapper.addView(passwordLayout)

        var committed = false
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                committed = true
                onCommit(
                    Credentials(
                        usernameEdit.text?.toString().orEmpty(),
                        passwordEdit.text?.toString().orEmpty(),
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!committed) onCancel() }
            .show()
        usernameEdit.requestFocus()
    }

    fun showString(
        context: Context,
        titleRes: Int,
        hintRes: Int,
        value: String,
        maxLines: Int,
        onCancel: () -> Unit = {},
        onCommit: (String) -> Unit,
    ) {
        val wrapper = buildWrapper(context)
        val layout = outlinedField(context, hintRes)
        val inputType = if (maxLines > 1) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val edit = editIn(layout, value, inputType, maxLines)
        wrapper.addView(layout)

        var committed = false
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                committed = true
                onCommit(edit.text?.toString().orEmpty().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!committed) onCancel() }
            .show()
        edit.requestFocus()
    }

    fun showValidatedString(
        context: Context,
        titleRes: Int,
        hintRes: Int,
        value: String,
        inputType: Int,
        validate: (String) -> Int?,
        onCommit: (String) -> Unit,
    ) {
        val wrapper = buildWrapper(context)
        val layout = outlinedField(context, hintRes)
        val edit = editIn(layout, value, inputType, maxLines = 1)
        wrapper.addView(layout)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        edit.doAfterTextChanged { layout.error = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entry = edit.text?.toString().orEmpty().trim()
                val errorRes = validate(entry)
                if (errorRes != null) {
                    layout.error = context.getString(errorRes)
                } else {
                    onCommit(entry)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        edit.requestFocus()
    }

    fun showKeyValue(
        context: Context,
        titleRes: Int,
        keyHintRes: Int,
        valueHintRes: Int,
        key: String,
        value: String,
        onCommit: (String, String) -> Unit,
    ) {
        val wrapper = buildWrapper(context)
        val keyLayout = outlinedField(context, keyHintRes)
        val keyEdit = editIn(keyLayout, key, InputType.TYPE_CLASS_TEXT, maxLines = 1)
        val valueLayout = outlinedField(context, valueHintRes, topMargin = fieldGap(context))
        val valueEdit = editIn(valueLayout, value, InputType.TYPE_CLASS_TEXT, maxLines = 1)
        wrapper.addView(keyLayout)
        wrapper.addView(valueLayout)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        keyEdit.doAfterTextChanged { keyLayout.error = null }
        valueEdit.doAfterTextChanged { valueLayout.error = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val k = keyEdit.text?.toString().orEmpty().trim()
                val v = valueEdit.text?.toString().orEmpty().trim()
                var valid = true
                if (k.isEmpty()) {
                    keyLayout.error = context.getString(keyHintRes)
                    valid = false
                }
                if (v.isEmpty()) {
                    valueLayout.error = context.getString(valueHintRes)
                    valid = false
                }
                if (valid) {
                    onCommit(k, v)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        keyEdit.requestFocus()
    }
}
