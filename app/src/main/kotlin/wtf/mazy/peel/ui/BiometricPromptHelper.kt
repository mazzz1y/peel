package wtf.mazy.peel.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils

internal class BiometricPromptHelper(private val activity: FragmentActivity) {
    companion object {
        fun getBiometricError(context: Context): String? {
            val biometricManager = BiometricManager.from(context)

            return when (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
                BiometricManager.BIOMETRIC_SUCCESS -> null
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    context.getString(R.string.no_biometric_keys_enrolled)

                else -> context.getString(R.string.no_biometric_devices)
            }
        }
    }

    fun showPrompt(
        funSuccess: BiometricPromptCallback,
        funFail: BiometricPromptCallback,
        promptTitle: String,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        funFail.execute()
                    }

                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        super.onAuthenticationSucceeded(result)
                        funSuccess.execute()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        NotificationUtils.showInfoSnackBar(
                            activity,
                            activity.getString(R.string.bioprompt_not_recognized),
                            Snackbar.LENGTH_SHORT,
                        )
                    }
                },
            )
        val promptInfo =
            PromptInfo.Builder()
                .setTitle(promptTitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        biometricPrompt.authenticate(promptInfo)
    }

    internal fun interface BiometricPromptCallback {
        fun execute()
    }
}
