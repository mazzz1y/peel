package wtf.mazy.peel.ui.picker

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import wtf.mazy.peel.ui.common.onImeAction
import wtf.mazy.peel.ui.webapplist.SearchBar
import wtf.mazy.peel.util.isImeVisible

internal interface PickerField {
    var onQueryChanged: (String) -> Unit
    var onConfirm: () -> Unit

    /** Puts the keyboard down if the field has it up; returns whether there was one. */
    fun lowerKeyboard(): Boolean
}

internal class TopPickerField(
    private val field: EditText,
    private val insets: WindowInsetsControllerCompat,
) : PickerField {
    override var onQueryChanged: (String) -> Unit = {}
    override var onConfirm: () -> Unit = {}

    init {
        field.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.doAfterTextChanged { onQueryChanged(it?.toString().orEmpty()) }
        field.onImeAction(EditorInfo.IME_ACTION_GO) { onConfirm() }
    }

    override fun lowerKeyboard(): Boolean {
        if (!field.hasFocus() || !field.isImeVisible) return false
        insets.hide(WindowInsetsCompat.Type.ime())
        field.clearFocus()
        return true
    }
}

internal class BottomPickerField(
    private val bar: SearchBar,
    private val insets: WindowInsetsControllerCompat,
) : PickerField {
    override var onQueryChanged: (String) -> Unit = {}
    override var onConfirm: () -> Unit = {}

    init {
        bar.onQueryChanged = { onQueryChanged(it) }
        bar.onSearch = { onConfirm() }
        bar.onKeyboardRequested = { insets.show(WindowInsetsCompat.Type.ime()) }
        bar.onActiveChanged = { active -> if (!active) insets.hide(WindowInsetsCompat.Type.ime()) }
    }

    override fun lowerKeyboard(): Boolean {
        if (!bar.hasFocus() || !bar.isImeVisible) return false
        bar.dropFocus()
        insets.hide(WindowInsetsCompat.Type.ime())
        return true
    }
}
