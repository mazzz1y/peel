package wtf.mazy.peel.util

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R

fun Context.toast(text: String, long: Boolean = false) {
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Context.toast(@StringRes res: Int, vararg args: Any, long: Boolean = false) {
    toast(getString(res, *args), long)
}

fun Activity.showUndoSnackBar(
    message: String,
    duration: Int = Snackbar.LENGTH_LONG,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
) {
    var isUndone = false
    Snackbar.make(findViewById(android.R.id.content), message, duration)
        .setAction(getString(R.string.undo)) {
            isUndone = true
            onUndo()
        }
        .addCallback(
            object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!isUndone) onCommit()
                }
            })
        .show()
}
