package wtf.mazy.peel.push

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.BrowserLauncher

object ServiceWorkerBridge {

    fun attach(runtime: GeckoRuntime, context: Context) {
        val appContext = context.applicationContext
        runtime.setServiceWorkerDelegate { url ->
            openWindow(appContext, url)
            GeckoResult.fromValue(null)
        }
    }

    private fun openWindow(context: Context, url: String) {
        App.appScope.launch(Dispatchers.Main.immediate) {
            DataManager.awaitReady()
            val claim = NotificationClickCoordinator.claim()
            val clicked = claim?.webAppUuid?.let { DataManager.webApp(it) }
            if (clicked != null) {
                BrowserLauncher.launch(clicked, context, url)
                return@launch
            }
            if (claim?.alreadyRouted == true) return@launch
            val sandbox = claim?.sandbox
            val target = if (sandbox != null) {
                NotificationTarget.forSandbox(sandbox.contextId, url)
            } else {
                NotificationTarget.forAddress(url)
            }
            if (target != null) {
                BrowserLauncher.launch(target, context, url)
            } else {
                context.startActivity(
                    NotificationClickLaunch.listIntent(context, url, sandbox)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
