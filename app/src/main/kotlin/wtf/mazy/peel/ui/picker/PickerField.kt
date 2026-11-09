package wtf.mazy.peel.ui.picker

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import wtf.mazy.peel.ui.webapplist.SearchBar
import wtf.mazy.peel.util.isImeVisible

internal class PickerField(
    private val bar: SearchBar,
    private val insets: WindowInsetsControllerCompat,
) {
    var onQueryChanged: (String) -> Unit = {}
    var onConfirm: () -> Unit = {}
    var onActiveChanged: (Boolean) -> Unit = {}

    init {
        bar.onQueryChanged = { onQueryChanged(it) }
        bar.onSearch = { onConfirm() }
        bar.onKeyboardRequested = { insets.show(WindowInsetsCompat.Type.ime()) }
        bar.onActiveChanged = { active ->
            if (!active) insets.hide(WindowInsetsCompat.Type.ime())
            onActiveChanged(active)
        }
    }

    /** Puts the keyboard down if the field has it up; returns whether there was one. */
    fun lowerKeyboard(): Boolean {
        if (!bar.hasFocus() || !bar.isImeVisible) return false
        bar.dropFocus()
        insets.hide(WindowInsetsCompat.Type.ime())
        return true
    }
}
