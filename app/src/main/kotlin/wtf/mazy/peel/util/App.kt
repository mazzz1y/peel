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
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.db.AppDatabase

class App : Application() {

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("App", "DataManager initialization failed", throwable)
        }
    )

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        SandboxManager.initialize(AppDatabase.getInstance(applicationContext).sandboxSlotDao())
        if (SandboxManager.currentSlotId == null) {
            @Suppress("DEPRECATION")
            SandboxManager.migrateDefaultWebViewDir()
        }
        appScope.launch {
            if (SandboxManager.currentSlotId != null) {
                DataManager.instance.initializeForSandbox(applicationContext)
            } else {
                DataManager.instance.initialize(applicationContext)
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set
    }
}
