package wtf.mazy.peel.gecko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.browser.SessionHostRegistry
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.AppPrefs

object SandboxManager {

    val usage = SandboxUsage { contextId ->
        if (isEphemeral(contextId)) clearIdle(App.appContext, contextId)
    }

    suspend fun clearSandboxData(context: Context, contextId: String): Boolean {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        SessionHostRegistry.closeSessionsFor(contextId)
        return clearContext(contextId)
    }

    fun enqueueSandboxClear(context: Context, contextId: String, scope: CoroutineScope) {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        scope.launch {
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

    fun clearIdleEphemeralSandboxes(context: Context) {
        val appContext = context.applicationContext
        App.appScope.launch(Dispatchers.Main.immediate) {
            DataManager.awaitReady()
            DataManager.state.value.ephemeralContextIds
                .filterNot(usage::isInUse)
                .forEach { clearIdle(appContext, it) }
        }
    }

    suspend fun clearNonSandboxData() {
        withContext(Dispatchers.IO) { App.appContext.cacheDir?.deleteRecursively() }
    }

    private fun isEphemeral(contextId: String): Boolean =
        DataManager.effectiveSandbox(contextId)?.ephemeralContextId == contextId

    // Persisted only as a fallback: a pending clear is flushed again later, possibly after a
    // window has reopened the sandbox.
    private fun clearIdle(context: Context, contextId: String) {
        PushBridge.onContextCleared(context, contextId)
        val runtime = GeckoRuntimeProvider.runtimeOrNull()
        val cleared = runtime != null &&
                runCatching { runtime.storageController.clearDataForSessionContext(contextId) }.isSuccess
        if (!cleared) AppPrefs.addPendingSandboxClear(context, contextId)
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
