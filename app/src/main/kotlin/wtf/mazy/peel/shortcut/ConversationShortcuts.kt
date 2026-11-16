package wtf.mazy.peel.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.identifyWebApp

/**
 * The long-lived shortcut that makes a web app's notifications a conversation. Its id is apart
 * from the pinned shortcut's, which the conversation would otherwise rename and re-icon, and it
 * is kept only as a cached long-lived shortcut, out of the launcher's menu.
 */
object ConversationShortcuts {

    fun person(webApp: WebApp, icon: Bitmap): Person = Person.Builder()
        .setKey(webApp.uuid)
        .setName(webApp.title)
        .setIcon(IconCompat.createWithBitmap(icon))
        .build()

    fun publish(context: Context, webApp: WebApp, person: Person): ShortcutInfoCompat {
        val shortcut = ShortcutInfoCompat.Builder(context, idFor(webApp.uuid))
            .setShortLabel(webApp.title)
            .setLongLived(true)
            .setPerson(person)
            .setIcon(person.icon)
            .setIntent(
                Intent().setClassName(context, Const.WEBAPP_SHORTCUT_ALIAS).identifyWebApp(webApp.uuid)
            )
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        return shortcut
    }

    fun hide(context: Context, shortcut: ShortcutInfoCompat) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcut.id))
    }

    fun remove(context: Context, webAppUuids: List<String>) {
        ShortcutManagerCompat.removeLongLivedShortcuts(context, webAppUuids.map(::idFor))
    }

    private fun idFor(webAppUuid: String) = "$webAppUuid#push"
}
