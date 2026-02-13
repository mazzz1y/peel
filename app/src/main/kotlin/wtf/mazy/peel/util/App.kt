package wtf.mazy.peel.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.webkit.WebView
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.SandboxManager
import wtf.mazy.peel.model.db.AppDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        DataManager.instance.initialize(applicationContext)
        SandboxManager.initialize(AppDatabase.getInstance(applicationContext).sandboxSlotDao())
        initWebViewDataDirectory()
    }

    private fun initWebViewDataDirectory() {
        val sandboxId = extractSandboxId() ?: return
        val uuid = SandboxManager.getSandboxUuid(sandboxId) ?: return

        try {
            WebView.setDataDirectorySuffix(uuid)
        } catch (_: IllegalStateException) {}
    }

    private fun extractSandboxId(): Int? {
        val processName = getProcessName()
        val match = Regex(":sandbox_(\\d+)$").find(processName)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set
    }
}
