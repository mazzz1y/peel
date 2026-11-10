package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import wtf.mazy.peel.ui.common.onImeAction

enum class InitialSelection { SELECT_ALL, CURSOR_AT_END }

data class InputDialogConfig(
    @param:StringRes val hintRes: Int = 0,
    @param:StringRes val titleRes: Int = 0,
    val title: CharSequence? = null,
    val prefill: String = "",
    @param:StringRes val positiveRes: Int = android.R.string.ok,
    val inputType: Int = InputType.TYPE_CLASS_TEXT,
    val initialSelection: InitialSelection = InitialSelection.SELECT_ALL,
    val allowEmpty: Boolean = false,
    /** Checked against the trimmed main field whenever any field changes; default non-blank. */
    val accepts: ((String) -> Boolean)? = null,
    val url: Boolean = false,
    val message: CharSequence? = null,
    val extraContent: ((LinearLayout) -> Unit)? = null,
    val onInputReady: ((TextInputEditText) -> Unit)? = null,
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
): androidx.appcompat.app.AlertDialog {
    val content = DialogContent.of(this)
    config.message?.let { content.message(it) }

    val container = content.column
    val inputLayout = TextInputLayout(container.context).apply {
        if (config.hintRes != 0) hint = getString(config.hintRes)
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
    if (config.url) UrlField.configure(input)
    inputLayout.addView(input)
    content.add(inputLayout)
    config.onInputReady?.invoke(input)
    config.extraContent?.invoke(container)

    val builder = MaterialAlertDialogBuilder(this)
        .setView(content.view)
        .setPositiveButton(config.positiveRes) { _, _ -> onPositive(input, container) }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
    if (config.titleRes != 0) builder.setTitle(config.titleRes)
    else config.title?.let { builder.setTitle(it) }
    val dialog = builder.create()

    // Reported after dismiss: the dialog's keyboard goes down with its window, and would take a
    // keyboard the caller raises in reply down with it. Takes the one dismiss-listener slot.
    config.onCancel?.let { onCancel ->
        var cancelled = false
        dialog.setOnCancelListener { cancelled = true }
        dialog.setOnDismissListener { if (cancelled) onCancel() }
    }

    dialog.dismissOnDestroyOf(this)
    dialog.show()

    val accepts = config.accepts ?: if (config.allowEmpty) null else String::isNotBlank
    if (accepts != null) {
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val render = { okButton.isEnabled = accepts(input.text.toString().trim()) }
        render()
        val fields = container.allEditTexts()
        fields.forEach { it.doAfterTextChanged { render() } }
        fields.filter(UrlField::isConfigured).forEach { field ->
            field.onImeAction(EditorInfo.IME_ACTION_DONE) { if (okButton.isEnabled) okButton.performClick() }
        }
    }
    return dialog
}

private fun View.allEditTexts(): List<EditText> = when (this) {
    is EditText -> listOf(this)
    is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allEditTexts() }
    else -> emptyList()
}
