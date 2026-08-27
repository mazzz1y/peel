package wtf.mazy.peel.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

// Activities here recreate on rotation; an imperative dialog left showing leaks its
// window. Dismissing from a lifecycle observer instead of an OnDismissListener keeps
// the single dismiss-listener slot free for callers.
fun <D : Dialog> D.dismissOnDestroyOf(activity: Activity): D {
    val owner = activity as? LifecycleOwner ?: return this
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(o: LifecycleOwner) {
            if (isShowing) dismiss()
        }
    }
    owner.lifecycle.addObserver(observer)
    window?.decorView?.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                owner.lifecycle.removeObserver(observer)
            }
        },
    )
    return this
}
