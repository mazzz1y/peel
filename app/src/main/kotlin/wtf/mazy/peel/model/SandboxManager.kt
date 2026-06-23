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
}
