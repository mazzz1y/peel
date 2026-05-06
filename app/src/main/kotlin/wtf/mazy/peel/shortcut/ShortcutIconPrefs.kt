package wtf.mazy.peel.shortcut

import android.content.Context
import androidx.core.content.edit
import wtf.mazy.peel.util.App

internal class ShortcutIconPrefs(context: Context, ownerUuid: String) {
    private val prefs =
        context.getSharedPreferences(prefsName(ownerUuid), Context.MODE_PRIVATE)

    data class Options(
        val logoSizeDp: Int,
        val fillBackground: Boolean,
        val adaptiveIcon: Boolean,
    )

    fun load(): Options? {
        if (!prefs.contains(KEY_LOGO_SIZE)) return null
        return Options(
            logoSizeDp = prefs.getInt(KEY_LOGO_SIZE, DEFAULT_LOGO_SIZE),
            fillBackground = prefs.getBoolean(KEY_FILL_BG, false),
            adaptiveIcon = prefs.getBoolean(KEY_ADAPTIVE, false),
        )
    }

    fun save(logoSizeDp: Int, fillBackground: Boolean, adaptiveIcon: Boolean) {
        prefs.edit {
            putInt(KEY_LOGO_SIZE, logoSizeDp)
            putBoolean(KEY_FILL_BG, fillBackground)
            putBoolean(KEY_ADAPTIVE, adaptiveIcon)
        }
    }

    companion object {
        const val DEFAULT_LOGO_SIZE = 48
        private const val KEY_LOGO_SIZE = "logo_size_dp"
        private const val KEY_FILL_BG = "fill_background"
        private const val KEY_ADAPTIVE = "adaptive_icon"

        private fun prefsName(ownerUuid: String) = "${ownerUuid}_shortcut_icon"

        fun clear(ownerUuid: String) {
            val ctx = App.appContext
            ctx.getSharedPreferences(prefsName(ownerUuid), Context.MODE_PRIVATE)
                .edit { clear() }
            ctx.deleteSharedPreferences(prefsName(ownerUuid))
        }
    }
}
