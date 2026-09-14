package wtf.mazy.peel.ui.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.BiometricPromptHelper
import wtf.mazy.peel.util.NotificationUtils

class BiometricUnlockController(
    private val activity: AppCompatActivity,
    private val getWebappUuid: () -> String?,
    private val onSuccess: () -> Unit,
    private val onFailure: () -> Unit,
) {
    var isPromptActive = false
        private set

    private var isReceiverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                unlocked.clear()
            }
        }
    }

    fun registerReceiver() {
        ContextCompat.registerReceiver(
            activity,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
    }

    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            activity.unregisterReceiver(screenOffReceiver)
            isReceiverRegistered = false
        }
    }

    fun isUnlocked(): Boolean {
        val uuid = getWebappUuid() ?: return false
        return unlocked.contains(uuid)
    }

    fun showPromptIfNeeded(
        isBiometricEnabled: Boolean,
        onPromptShown: () -> Unit,
    ): PromptOutcome {
        if (!isBiometricEnabled || isUnlocked()) return PromptOutcome.NOT_NEEDED

        val unavailableReason = BiometricPromptHelper.getBiometricError(activity)
        if (unavailableReason != null) {
            reportUnavailable(unavailableReason)
            return PromptOutcome.UNAVAILABLE
        }

        return showPrompt(onPromptShown)
    }

    private fun showPrompt(onPromptShown: () -> Unit): PromptOutcome {
        if (isPromptActive) return PromptOutcome.SHOWN
        isPromptActive = true
        onPromptShown()

        val shown = BiometricPromptHelper(activity)
            .showPrompt(
                {
                    setUnlocked()
                    isPromptActive = false
                    onSuccess()
                },
                {
                    isPromptActive = false
                    onFailure()
                },
                activity.getString(R.string.bioprompt_restricted_webapp),
            )

        if (!shown) {
            isPromptActive = false
            reportUnavailable(activity.getString(R.string.no_biometric_devices))
            return PromptOutcome.UNAVAILABLE
        }
        return PromptOutcome.SHOWN
    }

    private fun reportUnavailable(reason: String) {
        NotificationUtils.showToast(activity, reason, Toast.LENGTH_LONG)
        onFailure()
    }

    fun onStop() {
        val uuid = getWebappUuid() ?: return
        unlocked.remove(uuid)
    }

    fun resetForSwap() {
        isPromptActive = false
    }

    private fun setUnlocked() {
        val uuid = getWebappUuid() ?: return
        unlocked.add(uuid)
    }

    enum class PromptOutcome { SHOWN, NOT_NEEDED, UNAVAILABLE }

    companion object {
        private val unlocked = mutableSetOf<String>()
    }
}
