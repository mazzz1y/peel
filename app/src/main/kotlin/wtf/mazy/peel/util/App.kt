package wtf.mazy.peel.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import wtf.mazy.peel.activities.BrowserActivity
import wtf.mazy.peel.activities.ExtensionPageActivity
import wtf.mazy.peel.activities.IncomingLinkActivity
import wtf.mazy.peel.activities.NotificationClickActivity
import wtf.mazy.peel.activities.PopupActivity
import wtf.mazy.peel.activities.TrampolineActivity
import wtf.mazy.peel.activities.WebAppSettingsActivity
import wtf.mazy.peel.browser.CertStoreBridge
import wtf.mazy.peel.browser.ProxyRouterBridge
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.ui.extensions.ExtensionUiBridge
import wtf.mazy.peel.work.ExtensionUpdateScheduler

class App : Application() {

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("App", "DataManager initialization failed", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
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
        GeckoRuntimeProvider.extensionUi = ExtensionUiBridge
        ForegroundActivityTracker.onBackground = {
            GeckoRuntimeProvider.runtimeOrNull()?.let {
                SandboxManager.flushPendingClears(applicationContext, it)
            }
        }
        appScope.launch {
            DataManager.instance.initialize(applicationContext)
            ProxyRouterBridge.ensure(applicationContext)
            CertStoreBridge.ensure(applicationContext)
            ExtensionUpdateScheduler.apply(applicationContext)
        }
        appScope.launch {
            PushBridge.reconcile(applicationContext)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set
    }
}
