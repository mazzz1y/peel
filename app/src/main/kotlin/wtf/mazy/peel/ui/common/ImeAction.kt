package wtf.mazy.peel.ui.common

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView

// A hardware Enter reaches the listener as IME_NULL rather than as the field's own action.
fun TextView.onImeAction(action: Int, onSubmit: () -> Unit) {
    setOnEditorActionListener { _, actionId, event ->
        val submitted = actionId == action ||
                actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
        if (submitted) onSubmit()
        submitted
    }
}
