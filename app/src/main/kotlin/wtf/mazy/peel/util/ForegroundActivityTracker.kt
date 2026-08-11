package wtf.mazy.peel.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    private var startedCount = 0

    var onBackground: (() -> Unit)? = null

    val current: Activity?
        get() = currentRef?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedCount++
    }

    override fun onActivityResumed(activity: Activity) {
        currentRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        startedCount--
        if (startedCount == 0 && !activity.isChangingConfigurations) onBackground?.invoke()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
    }
}
