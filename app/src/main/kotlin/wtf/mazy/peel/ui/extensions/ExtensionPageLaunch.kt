package wtf.mazy.peel.ui.extensions

import android.content.Context
import android.content.Intent
import wtf.mazy.peel.util.ActivityRoutes

object ExtensionPageLaunch {
    const val EXTRA_EXTENSION_ID = "extension_id"
    const val EXTRA_SESSION_KEY = "session_key"
    const val EXTRA_TITLE = "title"

    fun intentForExtension(context: Context, extensionId: String): Intent =
        Intent(context, ActivityRoutes.extensionPage).putExtra(EXTRA_EXTENSION_ID, extensionId)

    fun intentForSession(context: Context, key: String, title: String): Intent =
        Intent(context, ActivityRoutes.extensionPage)
            .putExtra(EXTRA_SESSION_KEY, key)
            .putExtra(EXTRA_TITLE, title)
}
