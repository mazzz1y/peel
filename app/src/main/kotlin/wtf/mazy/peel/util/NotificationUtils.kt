package wtf.mazy.peel.util

import android.app.Activity
import android.view.Gravity
import android.widget.TextView
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
        val toast = Toast.makeText(a, text, toastDisplayDuration)
        toast.setGravity(Gravity.TOP, 0, 100)
        toast.show()
    }

    @JvmStatic
    fun showInfoSnackBar(activity: Activity, msg: String, duration: Int) {
        val snackBar = Snackbar.make(activity.findViewById(android.R.id.content), msg, duration)

        snackBar.setAction(activity.getString(R.string.ok)) { snackBar.dismiss() }

        val tv =
            snackBar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        tv.maxLines = 10
        snackBar.show()
    }
}
