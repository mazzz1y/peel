package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

enum class InitialSelection { SELECT_ALL, CURSOR_AT_END }

data class InputDialogConfig(
    @param:StringRes val hintRes: Int,
    @param:StringRes val titleRes: Int = 0,
    val prefill: String = "",
    @param:StringRes val positiveRes: Int = android.R.string.ok,
    val inputType: Int = InputType.TYPE_CLASS_TEXT,
    val initialSelection: InitialSelection = InitialSelection.SELECT_ALL,
    val allowEmpty: Boolean = false,
    val message: CharSequence? = null,
    val extraContent: ((LinearLayout) -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
)

fun Activity.showInputDialog(
    config: InputDialogConfig,
    onResult: (String) -> Unit,
) {
    showInputDialogRaw(config) { input, _ ->
        onResult(input.text.toString().trim())
    }
}

fun Activity.showInputDialogRaw(
    config: InputDialogConfig,
    onPositive: (TextInputEditText, View) -> Unit,
) {
    val content = DialogContent.of(this)
    config.message?.let { content.message(it) }

    val container = content.column
    val inputLayout = TextInputLayout(container.context).apply {
        hint = getString(config.hintRes)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    val input = TextInputEditText(inputLayout.context).apply {
        setText(config.prefill)
        setInputType(config.inputType)
        isSingleLine = true
        if (config.prefill.isNotEmpty()) {
            when (config.initialSelection) {
                InitialSelection.SELECT_ALL -> selectAll()
                InitialSelection.CURSOR_AT_END -> setSelection(config.prefill.length)
            }
        }
    }
    inputLayout.addView(input)
    content.add(inputLayout)
    config.extraContent?.invoke(container)

    val builder = MaterialAlertDialogBuilder(this)
        .setView(content.view)
        .setPositiveButton(config.positiveRes) { _, _ -> onPositive(input, container) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> config.onCancel?.invoke() }
    if (config.titleRes != 0) builder.setTitle(config.titleRes)
    val dialog = builder.create()

    if (config.onCancel != null) {
        dialog.setOnCancelListener { config.onCancel.invoke() }
    }

    dialog.show()

    if (!config.allowEmpty) {
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = config.prefill.isNotBlank()
        input.doAfterTextChanged { okButton.isEnabled = !it.isNullOrBlank() }
    }
}
