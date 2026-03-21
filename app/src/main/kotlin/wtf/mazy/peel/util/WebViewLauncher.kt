package wtf.mazy.peel.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.BiometricPromptHelper

object WebViewLauncher {
    fun startWebView(webapp: WebApp, c: Context, url: String? = null) {
        try {
            if (DataManager.instance.resolveEffectiveSettings(webapp).isBiometricProtection == true) {
                val error = BiometricPromptHelper.getBiometricError(c)
                if (error != null) {
                    showBiometricError(c, error)
                    return
                }
            }
            val intent = createWebViewIntent(webapp, c) ?: return
            if (url != null) intent.putExtra(Const.INTENT_TARGET_URL, url)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            c.startActivity(intent)
        } catch (_: Exception) {
            showLaunchError(c)
        }
    }

    fun buildPendingIntent(webapp: WebApp, context: Context): PendingIntent? {
        val intent = createWebViewIntent(webapp, context) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            webapp.uuid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun createWebViewIntent(webapp: WebApp, c: Context?): Intent? {
        if (c == null) return null
        val sandboxId = resolveSandboxId(webapp)
        val activityClass =
            SandboxManager.resolveActivityClass(c, sandboxId, webapp.uuid) ?: return null
        return buildIntent(c, activityClass, webapp.uuid)
    }

    fun resolveSandboxId(webapp: WebApp): String {
        if (webapp.isUseContainer) return webapp.uuid
        val group = webapp.groupUuid?.let { DataManager.instance.getGroup(it) }
        if (group != null && group.isUseContainer) return group.uuid
        return SandboxManager.DEFAULT_SANDBOX_ID
    }

    fun isEphemeralSandbox(webapp: WebApp): Boolean {
        if (webapp.isUseContainer) return webapp.isEphemeralSandbox
        val group = webapp.groupUuid?.let { DataManager.instance.getGroup(it) }
        return group?.isUseContainer == true && group.isEphemeralSandbox
    }

    private fun buildIntent(c: Context, activityClass: Class<*>, uuid: String): Intent {
        return Intent(c, activityClass).apply {
            putExtra(Const.INTENT_WEBAPP_UUID, uuid)
            data = "app://$uuid".toUri()
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
                c.getString(R.string.webview_activity_launch_failed),
                Toast.LENGTH_LONG,
            )
        }
    }
}
