package wtf.mazy.peel.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.BiometricPromptHelper

object BrowserLauncher {
    fun launch(webapp: WebApp, c: Context, url: String? = null) {
        try {
            if (DataManager.instance.resolveEffectiveSettings(webapp).isBiometricProtection == true) {
                val error = BiometricPromptHelper.getBiometricError(c)
                if (error != null) {
                    showBiometricError(c, error)
                    return
                }
            }
            val intent = createIntent(webapp, c) ?: return
            if (url != null) intent.putExtra(Const.INTENT_TARGET_URL, url)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            c.startActivity(intent)
        } catch (_: Exception) {
            showLaunchError(c)
        }
    }

    fun buildPendingIntent(webapp: WebApp, context: Context): PendingIntent? {
        val intent = createIntent(webapp, context) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            webapp.uuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun createIntent(webapp: WebApp, c: Context?): Intent? {
        if (c == null) return null
        return Intent(c, BrowserActivity::class.java).apply {
            putExtra(Const.INTENT_WEBAPP_UUID, webapp.uuid)
            data = "app://${webapp.uuid}".toUri()
            action = Intent.ACTION_VIEW
        }
    }

    private fun showBiometricError(c: Context, error: String) {
        if (c is AppCompatActivity) {
            NotificationUtils.showToast(c, error, Toast.LENGTH_LONG)
        }
    }

    private fun showLaunchError(c: Context) {
        if (c is AppCompatActivity) {
            NotificationUtils.showToast(
                c,
                c.getString(R.string.browser_launch_failed),
                Toast.LENGTH_LONG,
            )
        }
    }
}
