package wtf.mazy.peel.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.BiometricPromptHelper

object WebViewLauncher {
    @JvmStatic
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
        } catch (e: Exception) {
            showError(c, e)
        }
    }

    private fun showBiometricError(c: Context, error: String) {
        if (c is AppCompatActivity) {
            NotificationUtils.showInfoSnackBar(c, error, Snackbar.LENGTH_LONG)
        }
        Log.e("WebViewLauncher", "Biometric not available: $error")
    }

    @JvmStatic
    fun createWebViewIntent(webapp: WebApp, c: Context?): Intent? {
        if (c == null) return null

        val packageName = "wtf.mazy.peel.activities"
        val webviewClass =
            try {
                if (webapp.isUseContainer) {
                    val containerId = SandboxManager.findOrAssignContainer(c, webapp.uuid)
                    Class.forName("$packageName.SandboxActivity$containerId")
                } else {
                    Class.forName("$packageName.WebViewActivity")
                }
            } catch (e: ClassNotFoundException) {
                Log.e("WebViewLauncher", "WebView activity class not found", e)
                return null
            }

        return Intent(c, webviewClass).apply {
            putExtra(Const.INTENT_WEBAPP_UUID, webapp.uuid)
            data = "app://${webapp.uuid}".toUri()
            action = Intent.ACTION_VIEW
        }
    }

    private fun showError(c: Context, e: Exception) {
        if (c is AppCompatActivity) {
            NotificationUtils.showInfoSnackBar(
                c,
                c.getString(R.string.webview_activity_launch_failed),
                Snackbar.LENGTH_LONG,
            )
        }
        Log.e("WebViewLauncher", "Failed to start WebView", e)
    }
}
