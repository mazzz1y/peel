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
    fun showInfoSnackBar(activity: Activity, msg: String, duration: Int) {
        Snackbar.make(activity.findViewById(android.R.id.content), msg, duration)
            .setAction(activity.getString(R.string.ok)) {}
            .show()
    }
}
