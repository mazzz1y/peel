package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Activity.showInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    prefill: String = "",
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    @StringRes positiveRes: Int = android.R.string.ok,
    allowEmpty: Boolean = false,
    onResult: (String) -> Unit,
) {
    val input =
        EditText(this).apply {
            setText(prefill)
            setHint(hintRes)
            setInputType(inputType)
            if (prefill.isNotEmpty()) selectAll()
        }
    val ta = obtainStyledAttributes(intArrayOf(android.R.attr.dialogPreferredPadding))
    val padding = ta.getDimensionPixelSize(0, 0)
    ta.recycle()
    val container =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(input)
        }
    val dialog =
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(positiveRes) { _, _ -> onResult(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    dialog.show()
    if (!allowEmpty) {
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = prefill.isNotBlank()
        input.addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    okButton.isEnabled = !s.isNullOrBlank()
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
    }
}
