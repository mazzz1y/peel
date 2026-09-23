package wtf.mazy.peel.util

import android.content.Context
import androidx.biometric.BiometricManager
import wtf.mazy.peel.R

// BIOMETRIC_STRONG or DEVICE_CREDENTIAL is rejected outright on API 28-29,
// where BiometricManager reports BIOMETRIC_ERROR_UNSUPPORTED and PromptInfo.build() throws.
const val BIOMETRIC_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

fun Context.biometricUnavailableReason(): String? =
    when (BiometricManager.from(this).canAuthenticate(BIOMETRIC_AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> getString(R.string.no_biometric_keys_enrolled)
        else -> getString(R.string.no_biometric_devices)
    }
