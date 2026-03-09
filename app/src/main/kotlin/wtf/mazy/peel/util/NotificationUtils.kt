package wtf.mazy.peel.util

import android.app.Activity
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R

object NotificationUtils {

    @JvmStatic
    fun showToast(a: Activity, text: String) {
        showToast(a, text, Toast.LENGTH_LONG)
    }

    @JvmStatic
    fun showToast(a: Activity, text: String, toastDisplayDuration: Int) {
        Toast.makeText(a, text, toastDisplayDuration).show()
    }

    @JvmStatic
    fun showUndoSnackBar(
        activity: Activity,
        message: String,
        duration: Int = Snackbar.LENGTH_LONG,
        onUndo: () -> Unit,
        onCommit: () -> Unit,
    ) {
        var isUndone = false
        Snackbar.make(activity.findViewById(android.R.id.content), message, duration)
            .setAction(activity.getString(R.string.undo)) {
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
}
