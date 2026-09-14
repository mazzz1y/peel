package wtf.mazy.peel.ui

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.util.NotificationUtils

internal class BiometricPromptHelper(private val activity: FragmentActivity) {
    companion object {
        // BIOMETRIC_STRONG or DEVICE_CREDENTIAL is rejected outright on API 28-29,
        // where BiometricManager reports BIOMETRIC_ERROR_UNSUPPORTED and PromptInfo.build() throws.
        private const val ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun getBiometricError(context: Context): String? {
            val biometricManager = BiometricManager.from(context)

            return when (biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
                BiometricManager.BIOMETRIC_SUCCESS -> null
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    context.getString(R.string.no_biometric_keys_enrolled)

                else -> context.getString(R.string.no_biometric_devices)
            }
        }
    }

    /** Returns false when no prompt could be displayed; neither callback fires in that case. */
    fun showPrompt(
        funSuccess: BiometricPromptCallback,
        funFail: BiometricPromptCallback,
        promptTitle: String,
    ): Boolean {
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
                        NotificationUtils.showToast(
                            activity,
                            activity.getString(R.string.bioprompt_not_recognized),
                            Toast.LENGTH_SHORT,
                        )
                    }
                },
            )
        return try {
            val promptInfo =
                PromptInfo.Builder()
                    .setTitle(promptTitle)
                    .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                    .build()
            biometricPrompt.authenticate(promptInfo)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun interface BiometricPromptCallback {
        fun execute()
    }
}
