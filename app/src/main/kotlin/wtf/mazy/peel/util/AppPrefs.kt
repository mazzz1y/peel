package wtf.mazy.peel.util

import android.content.Context
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "peel_prefs"
    private const val KEY_EXTENSION_AUTO_UPDATE = "extension_auto_update"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_PUSH_PERMISSION_RESET_PENDING = "push_permission_reset_pending"
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

    fun dropPushPermissionResetPending(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PUSH_PERMISSION_RESET_PENDING)) return
        prefs.edit { remove(KEY_PUSH_PERMISSION_RESET_PENDING) }
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

    fun markSandboxClearAllPending(context: Context) {
        prefs(context).edit(commit = true) { putBoolean(KEY_SANDBOX_CLEAR_ALL_PENDING, true) }
    }

    @Synchronized
    fun removePendingSandboxClears(context: Context, contextIds: Set<String>) {
        prefs(context).edit {
            putStringSet(
                KEY_PENDING_SANDBOX_CLEARS,
                getPendingSandboxClears(context) - contextIds,
            )
            remove(KEY_SANDBOX_CLEAR_ALL_PENDING)
        }
    }
}
