package wtf.mazy.peel.ui.common

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator

class LoadingDialogController(
    private val activity: AppCompatActivity,
) {

    private var dialog: AlertDialog? = null

    fun show(@StringRes messageRes: Int) {
        if (activity.isFinishing || activity.isDestroyed || dialog?.isShowing == true) return
        val dp = activity.resources.displayMetrics.density
        val text = TextView(activity).apply {
            setText(messageRes)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (dp * 24).toInt(),
                (dp * 24).toInt(),
                (dp * 24).toInt(),
                (dp * 16).toInt(),
            )
            addView(
                CircularProgressIndicator(context).apply {
                    isIndeterminate = true
                    indicatorSize = (dp * 40).toInt()
                },
            )
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = (dp * 16).toInt() },
            )
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(layout)
            .setCancelable(false)
            .show()
    }

    fun dismiss() {
        kotlin.runCatching { dialog?.dismiss() }
        dialog = null
    }
}
