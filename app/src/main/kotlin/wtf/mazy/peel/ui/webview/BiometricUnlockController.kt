package wtf.mazy.peel.ui.webview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.BiometricPromptHelper

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

    fun showPromptIfNeeded(isBiometricEnabled: Boolean, armOverlay: () -> Unit) {
        if (!isBiometricEnabled || isUnlocked()) return
        showPrompt(armOverlay)
    }

    private fun showPrompt(armOverlay: () -> Unit) {
        if (isPromptActive) return
        isPromptActive = true
        armOverlay()

        BiometricPromptHelper(activity)
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

    companion object {
        private val unlocked = mutableSetOf<String>()
    }
}
