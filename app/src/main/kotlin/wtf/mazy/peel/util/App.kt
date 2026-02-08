package wtf.mazy.peel.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.WebView
import wtf.mazy.peel.model.SandboxManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        initWebViewDataDirectory()
    }

    private fun initWebViewDataDirectory() {
        val sandboxId = extractSandboxId() ?: return
        val uuid = SandboxManager.getSandboxUuid(sandboxId) ?: return

        try {
            WebView.setDataDirectorySuffix(uuid)
        } catch (e: IllegalStateException) {
            Log.w("App", "WebView data directory suffix already set", e)
        }
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
