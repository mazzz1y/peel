package wtf.mazy.peel.model

import android.content.Context
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.util.App

object SandboxManager {

    fun clearSandboxData(context: Context, contextId: String): Boolean {
        return try {
            val runtime = GeckoRuntimeProvider.getRuntime(context)
            runtime.storageController.clearDataForSessionContext(contextId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearAllSandboxData(context: Context) {
        try {
            val runtime = GeckoRuntimeProvider.getRuntime(context)
            runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        } catch (_: Exception) {
        }
    }

    fun clearNonSandboxData() {
        App.appContext.cacheDir?.deleteRecursively()
    }

    fun getSandboxDataDir(contextId: String): java.io.File {
        return java.io.File(App.appContext.filesDir.parent, "app_webview_$contextId")
    }

    fun hasStoredData(context: Context, contextId: String): Boolean {
        return getSandboxDataDir(contextId).exists()
    }

    @Suppress("UNUSED_PARAMETER")
    fun releaseSandbox(context: Context, uuid: String) {
    }
}
