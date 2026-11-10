package wtf.mazy.peel.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.activities.ExtensionPageActivity
import wtf.mazy.peel.activities.IncomingLinkActivity
import wtf.mazy.peel.activities.NotificationClickActivity
import wtf.mazy.peel.activities.PopupActivity
import wtf.mazy.peel.activities.TrampolineActivity
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.browser.AppDataSideEffects
import wtf.mazy.peel.browser.CertStoreBridge
import wtf.mazy.peel.browser.ProxyRouterBridge
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.gecko.SandboxManager
import wtf.mazy.peel.model.DataLoad
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.ui.AppLock
import wtf.mazy.peel.ui.common.DataFailureController
import wtf.mazy.peel.ui.extensions.ExtensionUiBridge
import wtf.mazy.peel.work.ExtensionUpdateScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                Log.e("App", "Background work failed", throwable)
            }
        )
        ActivityRoutes.install(
            browser = BrowserActivity::class.java,
            popup = PopupActivity::class.java,
            extensionPage = ExtensionPageActivity::class.java,
            linkRouter = IncomingLinkActivity::class.java,
            trampoline = TrampolineActivity::class.java,
            webAppSettings = WebAppSettingsActivity::class.java,
            notificationClick = NotificationClickActivity::class.java,
        )
        if (getProcessName() != packageName) {
            return
        }
        registerActivityLifecycleCallbacks(ForegroundActivityTracker)
        DataFailureController.install(this)
        AppLock.lockOnScreenOff(this)
        GeckoRuntimeProvider.extensionUi = ExtensionUiBridge
        DataManager.sideEffects = AppDataSideEffects(applicationContext, appScope)
        appScope.launch { DataManager.initialize(applicationContext) }
        // Runs on the first successful load, which may only come after a retry. Each task is its
        // own job, so one failing does not cancel the others.
        appScope.launch {
            DataManager.load.first { it == DataLoad.Ready }
            appScope.launch { PushBridge.reconcile(applicationContext) }
            appScope.launch(Dispatchers.Main.immediate) {
                SandboxManager.clearIdleEphemeralSandboxes(applicationContext)
            }
            appScope.launch { ProxyRouterBridge.ensure(applicationContext) }
            appScope.launch { CertStoreBridge.ensure(applicationContext) }
            ExtensionUpdateScheduler.apply(applicationContext)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set

        lateinit var appScope: CoroutineScope
            private set
    }
}
