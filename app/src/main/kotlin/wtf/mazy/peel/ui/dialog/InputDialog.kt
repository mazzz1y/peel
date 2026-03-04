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

fun Activity.showInputDialog(
    @StringRes titleRes: Int = 0,
    @StringRes hintRes: Int,
    prefill: String = "",
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    @StringRes positiveRes: Int = android.R.string.ok,
    allowEmpty: Boolean = false,
    onResult: (String) -> Unit,
) {
    buildInputDialog(titleRes, hintRes, prefill, inputType, positiveRes, allowEmpty) { input, _ ->
        onResult(input.text.toString().trim())
    }
}

fun Activity.buildInputDialog(
    @StringRes titleRes: Int = 0,
    @StringRes hintRes: Int,
    prefill: String = "",
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    @StringRes positiveRes: Int = android.R.string.ok,
    allowEmpty: Boolean = false,
    extraContent: ((LinearLayout) -> Unit)? = null,
    onPositive: (TextInputEditText, View) -> Unit,
) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.dialogPreferredPadding))
        val padding = ta.getDimensionPixelSize(0, 0)
        ta.recycle()
        setPadding(padding, padding, padding, padding)
    }

    val inputLayout = TextInputLayout(container.context).apply {
        hint = getString(hintRes)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    val input = TextInputEditText(inputLayout.context).apply {
        setText(prefill)
        setInputType(inputType)
        isSingleLine = true
        if (prefill.isNotEmpty()) selectAll()
    }
    inputLayout.addView(input)
    container.addView(inputLayout)
    extraContent?.invoke(container)

    val builder = MaterialAlertDialogBuilder(this)
        .setView(container)
        .setPositiveButton(positiveRes) { _, _ -> onPositive(input, container) }
        .setNegativeButton(android.R.string.cancel, null)
    if (titleRes != 0) builder.setTitle(titleRes)
    val dialog = builder.create()

    dialog.show()

    if (!allowEmpty) {
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = prefill.isNotBlank()
        input.doAfterTextChanged { okButton.isEnabled = !it.isNullOrBlank() }
    }
}
