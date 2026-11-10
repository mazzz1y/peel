package wtf.mazy.peel.ui.dialog

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.WebOrigin

object UrlField {

    val FULL: (String) -> Boolean = { WebOrigin.of(it) != null }

    /** Also a bare host such as `example.com`, which is given `https://`. */
    val TYPED: (String) -> Boolean = { TypedUrl.parse(it) != null }

    private const val MAX_LINES = 6

    private const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

    // Address fields are recognised by their input type, so a dialog can make Done confirm.
    fun isConfigured(field: EditText): Boolean = field.inputType == INPUT_TYPE

    fun configure(field: EditText) {
        field.inputType = INPUT_TYPE
        field.setHorizontallyScrolling(false)
        field.maxLines = MAX_LINES
        field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.setSelection(field.text.length)
    }
}
