package wtf.mazy.peel.util

import android.content.Intent
import androidx.core.net.toUri

// Carried as both an extra and the intent data: Android persists a task's base intent without its
// extras, so only the data survives a relaunch into a restored task.
fun Intent.identifyWebApp(uuid: String): Intent = apply {
    putExtra(Const.INTENT_WEBAPP_UUID, uuid)
    data = "${Const.WEBAPP_URI_SCHEME}://$uuid".toUri()
    action = Intent.ACTION_VIEW
}

fun Intent.webAppUuid(): String? =
    getStringExtra(Const.INTENT_WEBAPP_UUID)
        ?: data?.takeIf { it.scheme == Const.WEBAPP_URI_SCHEME }?.host
