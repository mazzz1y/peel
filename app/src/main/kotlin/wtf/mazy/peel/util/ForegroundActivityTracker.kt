package wtf.mazy.peel.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    val current: Activity?
        get() = currentRef?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        currentRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
    }
}
