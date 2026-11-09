package wtf.mazy.peel.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataLoad
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.ForegroundActivityTracker
import wtf.mazy.peel.util.toast
import java.lang.ref.WeakReference

/** Tells the user when Peel's data could not be opened, on whichever screen is in front. */
object DataFailureController : Application.ActivityLifecycleCallbacks {

    private var shown: WeakReference<AlertDialog>? = null

    private val failed: Boolean
        get() = DataManager.load.value is DataLoad.Failed

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        App.appScope.launch(Dispatchers.Main) {
            DataManager.load.collect { load ->
                if (load is DataLoad.Failed) {
                    ForegroundActivityTracker.current?.let(::explain)
                } else {
                    shown?.get()?.dismiss()
                    shown = null
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (failed) explain(activity)
    }

    private fun explain(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity is DataRoutingScreen) {
            activity.toast(R.string.data_open_failed_title, long = true)
            activity.finish()
            return
        }
        val current = shown?.get()
        if (current?.isShowing == true && current.ownerActivity === activity) return
        current?.dismiss()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.data_open_failed_title)
            .setMessage(R.string.data_open_failed_message)
            .setCancelable(false)
            .setPositiveButton(R.string.retry) { _, _ ->
                shown = null
                App.appScope.launch { DataManager.initialize(App.appContext) }
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                shown = null
                activity.finishAffinity()
            }
            .create()
        dialog.setOwnerActivity(activity)
        dialog.show()
        shown = WeakReference(dialog)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        val dialog = shown?.get() ?: return
        if (dialog.ownerActivity !== activity) return
        dialog.dismiss()
        shown = null
    }
}
