package wtf.mazy.peel.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.browser.SessionHostRegistry
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.AppPrefs

object SandboxManager {

    suspend fun clearSandboxData(context: Context, contextId: String): Boolean {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        SessionHostRegistry.closeSessionsFor(contextId)
        return clearContext(contextId)
    }

    fun enqueueSandboxClear(context: Context, contextId: String) {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        DataManager.instance.appScope.launch {
            SessionHostRegistry.closeSessionsFor(contextId)
            clearContext(contextId)
        }
    }

    suspend fun clearAllSandboxData(context: Context) {
        PushBridge.onAllContextsCleared(context)
        AppPrefs.markSandboxClearAllPending(context)
        clearAll()
    }

    fun flushPendingClears(context: Context, runtime: GeckoRuntime) {
        val pending = AppPrefs.getPendingSandboxClears(context)
        val clearAll = AppPrefs.isSandboxClearAllPending(context)
        if (!clearAll && pending.isEmpty()) return
        runCatching {
            if (clearAll) {
                runtime.storageController.clearData(StorageController.ClearFlags.ALL)
            } else {
                pending.forEach { runtime.storageController.clearDataForSessionContext(it) }
            }
            AppPrefs.removePendingSandboxClears(context, pending)
        }
    }

    suspend fun clearNonSandboxData() {
        withContext(Dispatchers.IO) { App.appContext.cacheDir?.deleteRecursively() }
    }

    private suspend fun clearContext(contextId: String): Boolean =
        withContext(Dispatchers.Main) {
            val runtime = GeckoRuntimeProvider.runtimeOrNull() ?: return@withContext true
            runCatching {
                runtime.storageController.clearDataForSessionContext(contextId)
            }.isSuccess
        }

    private suspend fun clearAll() {
        withContext(Dispatchers.Main) {
            val runtime = GeckoRuntimeProvider.runtimeOrNull() ?: return@withContext
            runCatching {
                runtime.storageController.clearData(StorageController.ClearFlags.ALL)
            }
        }
    }
}
