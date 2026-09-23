package wtf.mazy.peel.ui.settings

import android.app.Activity
import android.content.Intent
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.model.ApplyTiming
import wtf.mazy.peel.model.ApplyTimingRegistry
import wtf.mazy.peel.util.restartApp

fun showApplyTimingSnackbar(activity: Activity, data: Intent?, hasLiveWebApps: Boolean) {
    val timingName = data?.getStringExtra(ApplyTimingRegistry.EXTRA_APPLY_TIMING) ?: return
    val message = when (ApplyTiming.valueOf(timingName)) {
        ApplyTiming.IMMEDIATE -> return
        ApplyTiming.WEBAPP_RESTART ->
            if (hasLiveWebApps) R.string.setting_requires_webapp_restart else return

        ApplyTiming.PEEL_RESTART -> R.string.setting_requires_peel_restart
    }
    Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        .setAction(R.string.restart) { restartApp(activity) }
        .show()
}
