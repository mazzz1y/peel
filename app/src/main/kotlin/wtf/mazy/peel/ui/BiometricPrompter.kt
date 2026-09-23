package wtf.mazy.peel.ui

import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.util.BIOMETRIC_AUTHENTICATORS
import wtf.mazy.peel.util.toast

internal class BiometricPrompter(private val activity: FragmentActivity) {
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
                        activity.toast(R.string.bioprompt_not_recognized)
                    }
                },
            )
        return try {
            val promptInfo =
                PromptInfo.Builder()
                    .setTitle(promptTitle)
                    .setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
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
