package wtf.mazy.peel.ui.proxy

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf

object ProxyPickerDialog {

    fun <P : ProxyChoice> show(
        activity: Activity,
        options: List<ProxyOption<P>>,
        current: P,
        onPick: (P) -> Unit,
    ) {
        val checked = options.indexOf(ProxyOptions.current(options, current))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.proxy)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onPick(options[which].choice)
            }
            .show()
            .dismissOnDestroyOf(activity)
    }
}
