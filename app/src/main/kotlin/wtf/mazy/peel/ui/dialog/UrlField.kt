package wtf.mazy.peel.ui.dialog

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import wtf.mazy.peel.ui.common.onImeAction
import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.WebOrigin

object UrlField {

    val FULL: (String) -> Boolean = { WebOrigin.of(it) != null }

    val TYPED: (String) -> Boolean = { TypedUrl.parse(it) != null }

    private const val MAX_LINES = 6

    fun configure(field: EditText) {
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        field.setHorizontallyScrolling(false)
        field.maxLines = MAX_LINES
        field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.setSelection(field.text.length)
    }

    // Call once the dialog is shown: the input dialog's own non-blank rule watches its field
    // too, and this one has to run after it to have the last word.
    fun gate(
        field: EditText,
        confirm: Button,
        accepts: (String) -> Boolean,
        filled: List<EditText> = emptyList(),
    ) {
        fun render() {
            confirm.isEnabled = accepts(field.text.toString().trim()) &&
                    filled.all { it.text.isNotBlank() }
        }
        render()
        (filled + field).forEach { it.doAfterTextChanged { render() } }
        field.onImeAction(EditorInfo.IME_ACTION_DONE) {
            if (confirm.isEnabled) confirm.performClick()
        }
    }
}
