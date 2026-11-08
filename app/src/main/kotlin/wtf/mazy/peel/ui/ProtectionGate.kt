package wtf.mazy.peel.ui

import androidx.fragment.app.FragmentActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
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
        val unavailable = activity.biometricUnavailableReason()
        if (unavailable != null) {
            activity.toast(unavailable, long = true)
            onDenied()
            return
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
    }

    fun isProtected(webApp: WebApp): Boolean =
        DataManager.effectiveSettings(webApp).biometricProtection

    fun isProtected(group: WebAppGroup): Boolean =
        group.settings.isBiometricProtection ?: DataManager.globalEffectiveSettings.biometricProtection

    val isProtectedGlobally: Boolean
        get() = DataManager.globalEffectiveSettings.biometricProtection

    val isAnythingProtected: Boolean
        get() = isProtectedGlobally ||
                DataManager.groups.any { it.settings.isBiometricProtection == true } ||
                DataManager.webApps.any(::isProtected)
}
