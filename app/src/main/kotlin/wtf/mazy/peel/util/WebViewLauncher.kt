package wtf.mazy.peel.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.WebViewActivity
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.BiometricPromptHelper

object WebViewLauncher {
    fun startWebView(webapp: WebApp, c: Context) {
        try {
            if (webapp.effectiveSettings.isBiometricProtection == true) {
                val error = BiometricPromptHelper.getBiometricError(c)
                if (error != null) {
                    showBiometricError(c, error)
                    return
                }
            }
            val intent = createWebViewIntent(webapp, c) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (c !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            c.startActivity(intent)
        } catch (_: Exception) {
            showLaunchError(c)
        }
    }

    private fun showBiometricError(c: Context, error: String) {
        if (c is AppCompatActivity) {
            NotificationUtils.showInfoSnackBar(c, error, Snackbar.LENGTH_LONG)
        }
    }

    fun createWebViewIntent(webapp: WebApp, c: Context?): Intent? {
        if (c == null) return null

        val activityClass =
            if (webapp.isUseContainer) {
                val containerId = SandboxManager.findOrAssignContainer(c, webapp.uuid)
                resolveSandboxClass(containerId) ?: return null
            } else {
                WebViewActivity::class.java
            }

        return buildIntent(c, activityClass, webapp.uuid)
    }

    private fun buildIntent(c: Context, activityClass: Class<*>, uuid: String): Intent {
        return Intent(c, activityClass).apply {
            putExtra(Const.INTENT_WEBAPP_UUID, uuid)
            data = "app://$uuid".toUri()
            action = Intent.ACTION_VIEW
        }
    }

    private fun resolveSandboxClass(containerId: Int): Class<*>? {
        return try {
            Class.forName("wtf.mazy.peel.activities.SandboxActivity$containerId")
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun showLaunchError(c: Context) {
        if (c is AppCompatActivity) {
            NotificationUtils.showInfoSnackBar(
                c,
                c.getString(R.string.webview_activity_launch_failed),
                Snackbar.LENGTH_LONG,
            )
        }
    }
}
