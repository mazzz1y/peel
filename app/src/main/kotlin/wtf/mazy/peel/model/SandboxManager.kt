package wtf.mazy.peel.model

import android.content.Context
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.util.App

object SandboxManager {

    fun clearSandboxData(context: Context, contextId: String): Boolean {
        PushBridge.onContextCleared(context, contextId)
        return runCatching {
            GeckoRuntimeProvider.getRuntime(context)
                .storageController
                .clearDataForSessionContext(contextId)
        }.isSuccess
    }

    fun clearAllSandboxData(context: Context) {
        PushBridge.onAllContextsCleared(context)
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
