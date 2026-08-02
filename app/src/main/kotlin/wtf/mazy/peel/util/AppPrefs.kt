package wtf.mazy.peel.util

import android.content.Context
import androidx.core.content.edit

object AppPrefs {
    private const val PREFS = "peel_prefs"
    private const val KEY_EXTENSION_AUTO_UPDATE = "extension_auto_update"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_PUSH_PERMISSION_RESET_PENDING = "push_permission_reset_pending"

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

    fun isPushPermissionResetPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PUSH_PERMISSION_RESET_PENDING, false)

    fun setPushPermissionResetPending(context: Context, pending: Boolean) {
        prefs(context).edit { putBoolean(KEY_PUSH_PERMISSION_RESET_PENDING, pending) }
    }
}
