package wtf.mazy.peel.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp

object BrowserLauncher {
    fun launch(webApp: WebApp, c: Context, url: String? = null, fromMenu: Boolean = false) {
        val target = url?.let { WebUrl.accept(it) ?: return showLaunchError(c) }
        try {
            if (DataManager.effectiveSettings(webApp).biometricProtection) {
                val error = c.biometricUnavailableReason()
                if (error != null) {
                    showBiometricError(c, error)
                    return
                }
            }
            val intent = createIntent(webApp, c) ?: return
            if (target != null) intent.putExtra(Const.INTENT_TARGET_URL, target)
            if (fromMenu) intent.putExtra(Const.INTENT_LAUNCHED_FROM_MENU, true)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            c.startActivity(intent)
        } catch (_: Exception) {
            showLaunchError(c)
        }
    }

    fun launchIncognito(c: Context, url: String) {
        val target = WebUrl.accept(url) ?: return showLaunchError(c)
        val uuid = DataManager.registerTransientWebApp(
            baseUrl = target,
            title = target.siteHost() ?: target,
            privateSession = true,
        )
        c.startActivity(
            Intent(c, ActivityRoutes.browser)
                .identifyWebApp(uuid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun buildPendingIntent(webApp: WebApp, context: Context): PendingIntent? {
        val intent = createIntent(webApp, context) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            webApp.uuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun createIntent(webApp: WebApp, c: Context?): Intent? {
        if (c == null) return null
        return Intent(c, ActivityRoutes.browser).identifyWebApp(webApp.uuid)
    }

    private fun showBiometricError(c: Context, error: String) {
        if (c is AppCompatActivity) {
            c.toast(error, long = true)
        }
    }

    private fun showLaunchError(c: Context) {
        if (c is AppCompatActivity) {
            c.toast(R.string.browser_launch_failed, long = true)
        }
    }
}
