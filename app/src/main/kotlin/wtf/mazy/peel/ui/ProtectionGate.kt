package wtf.mazy.peel.ui

import androidx.fragment.app.FragmentActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.util.biometricUnavailableReason
import wtf.mazy.peel.util.toast

object ProtectionGate {

    fun require(
        activity: FragmentActivity,
        needed: Boolean,
        unlocked: Boolean = false,
        onDenied: () -> Unit = {},
        onPass: () -> Unit,
    ) {
        if (!needed || unlocked) {
            onPass()
            return
        }
        prompt(activity, onPass, onDenied)
    }

    /** Returns whether the prompt is showing; when it can't be, the user is told why. */
    fun prompt(activity: FragmentActivity, onPass: () -> Unit, onDenied: () -> Unit): Boolean {
        val unavailable = activity.biometricUnavailableReason()
        if (unavailable != null) {
            activity.toast(unavailable, long = true)
            onDenied()
            return false
        }
        val shown = BiometricPrompter(activity).showPrompt(
            { onPass() },
            { onDenied() },
            activity.getString(R.string.bioprompt_restricted_webapp),
        )
        if (!shown) {
            activity.toast(R.string.no_biometric_devices, long = true)
            onDenied()
        }
        return shown
    }
}
