package wtf.mazy.peel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Which protected web apps are unlocked. A web app stays unlocked while any of its windows is
 * on screen and locks when the last one leaves; every web app locks when the screen turns off.
 * Main thread only.
 */
object AppLock {
    private val unlocked = mutableSetOf<String>()
    private val visibleWindows = mutableMapOf<String, Int>()

    fun isUnlocked(uuid: String): Boolean = uuid in unlocked

    fun unlock(uuid: String) {
        unlocked += uuid
    }

    fun windowShown(uuid: String) {
        visibleWindows[uuid] = (visibleWindows[uuid] ?: 0) + 1
    }

    fun windowHidden(uuid: String) {
        val remaining = (visibleWindows[uuid] ?: 0) - 1
        if (remaining > 0) {
            visibleWindows[uuid] = remaining
        } else {
            visibleWindows -= uuid
            unlocked -= uuid
        }
    }

    fun lockAll() = unlocked.clear()

    fun lockOnScreenOff(context: Context) {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = lockAll()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
