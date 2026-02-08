package wtf.mazy.peel.util

import android.content.Context
import android.content.pm.ShortcutManager
import wtf.mazy.peel.R

object ShortcutIconUtils {
    @JvmStatic
    fun deleteShortcuts(removableWebAppUuids: List<String>, context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        for (info in manager.pinnedShortcuts) {
            val uuid = info.intent?.getStringExtra(Const.INTENT_WEBAPP_UUID)
            if (uuid != null && removableWebAppUuids.contains(uuid)) {
                manager.disableShortcuts(
                    listOf(info.id),
                    context.getString(R.string.webapp_already_deleted),
                )
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
