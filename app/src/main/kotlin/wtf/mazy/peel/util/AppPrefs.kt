package wtf.mazy.peel.util

import android.content.Context
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "peel_prefs"
    private const val KEY_EXTENSION_AUTO_UPDATE = "extension_auto_update"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_PENDING_SANDBOX_CLEARS = "pending_sandbox_clears"
    private const val KEY_SANDBOX_CLEAR_ALL_PENDING = "sandbox_clear_all_pending"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isExtensionAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTENSION_AUTO_UPDATE, true)

    fun setExtensionAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_EXTENSION_AUTO_UPDATE, enabled) }
    }

    fun isPushEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PUSH_ENABLED, false)

    fun setPushEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_PUSH_ENABLED, enabled) }
    }

    fun getPendingSandboxClears(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PENDING_SANDBOX_CLEARS, emptySet())!!.toSet()

    @Synchronized
    fun addPendingSandboxClear(context: Context, contextId: String) {
        prefs(context).edit(commit = true) {
            putStringSet(
                KEY_PENDING_SANDBOX_CLEARS,
                getPendingSandboxClears(context) + contextId,
            )
        }
    }

    fun isSandboxClearAllPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SANDBOX_CLEAR_ALL_PENDING, false)

    @Synchronized
    fun markSandboxClearAllPending(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_SANDBOX_CLEAR_ALL_PENDING, true) }
    }

    @Synchronized
    fun forgetPendingSandboxClear(context: Context, contextId: String) {
        prefs(context).edit(commit = true) {
            putStringSet(
                KEY_PENDING_SANDBOX_CLEARS,
                getPendingSandboxClears(context) - contextId,
            )
        }
    }

    @Synchronized
    fun forgetSandboxClearAll(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_PENDING_SANDBOX_CLEARS)
            remove(KEY_SANDBOX_CLEAR_ALL_PENDING)
        }
    }
}
