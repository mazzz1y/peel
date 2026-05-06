package wtf.mazy.peel.shortcut

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import wtf.mazy.peel.R
import wtf.mazy.peel.util.Const

object ShortcutIconUtils {
    @JvmStatic
    fun deleteShortcuts(removableUuids: List<String>, context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        for (info in manager.pinnedShortcuts) {
            val uuid = info.intent?.let {
                it.getStringExtra(Const.INTENT_WEBAPP_UUID)
                    ?: it.getStringExtra(Const.INTENT_GROUP_UUID)
            }
            if (uuid != null && removableUuids.contains(uuid)) {
                manager.disableShortcuts(
                    listOf(info.id),
                    context.getString(R.string.webapp_already_deleted),
                )
                ShortcutIconPrefs.clear(uuid)
                ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(info.id))
            }
        }
    }

    @JvmStatic
    fun getWidthFromIcon(sizeString: String): Int {
        var xIndex = sizeString.indexOf("x")
        if (xIndex == -1) xIndex = sizeString.indexOf("×")
        if (xIndex == -1) xIndex = sizeString.indexOf("*")

        if (xIndex == -1) return 1
        val width = sizeString.take(xIndex)

        return width.toIntOrNull() ?: 1
    }
}
