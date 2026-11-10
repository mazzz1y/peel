package wtf.mazy.peel.gecko

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.browser.SessionHostRegistry
import wtf.mazy.peel.gecko.GeckoRuntimeProvider.awaitVoid
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.push.PushBridge
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.AppPrefs

object SandboxManager {

    private const val TAG = "SandboxManager"

    val usage = SandboxUsage { contextId ->
        if (isEphemeral(contextId)) clearIdle(App.appContext, contextId)
    }

    suspend fun clearSandboxData(context: Context, contextId: String): Boolean {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        SessionHostRegistry.closeSessionsFor(contextId)
        return wipe(context.applicationContext, contextId)
    }

    fun enqueueSandboxClear(context: Context, contextId: String, scope: CoroutineScope) {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        scope.launch {
            SessionHostRegistry.closeSessionsFor(contextId)
            wipe(context.applicationContext, contextId)
        }
    }

    suspend fun clearAllSandboxData(context: Context) {
        PushBridge.onAllContextsCleared(context)
        AppPrefs.markSandboxClearAllPending(context)
        wipe(context.applicationContext, contextId = null)
    }

    // Runs as the engine starts, before any session exists, so a clear that could not complete
    // earlier cannot wipe data created after it.
    fun flushPendingClears(context: Context, runtime: GeckoRuntime) {
        val appContext = context.applicationContext
        val clearAll = AppPrefs.isSandboxClearAllPending(appContext)
        val pending = AppPrefs.getPendingSandboxClears(appContext)
        if (!clearAll && pending.isEmpty()) return
        App.appScope.launch(Dispatchers.Main.immediate) {
            if (clearAll) {
                wipeWith(appContext, runtime, contextId = null)
            } else {
                pending.forEach { wipeWith(appContext, runtime, it) }
            }
        }
    }

    /** Call once the data is open, on the main thread. */
    fun clearIdleEphemeralSandboxes(context: Context) {
        val appContext = context.applicationContext
        DataManager.state.value.ephemeralContextIds
            .filterNot(usage::isInUse)
            .forEach { clearIdle(appContext, it) }
    }

    suspend fun clearNonSandboxData() {
        withContext(Dispatchers.IO) { App.appContext.cacheDir?.deleteRecursively() }
    }

    private fun isEphemeral(contextId: String): Boolean =
        DataManager.effectiveSandbox(contextId)?.ephemeralContextId == contextId

    private fun clearIdle(context: Context, contextId: String) {
        PushBridge.onContextCleared(context, contextId)
        AppPrefs.addPendingSandboxClear(context, contextId)
        App.appScope.launch(Dispatchers.Main.immediate) { wipe(context, contextId) }
    }

    /**
     * Wipes one sandbox, or every sandbox when [contextId] is null, and forgets its pending
     * record once the engine has taken the request. Without a running engine the record is left
     * for [flushPendingClears]. Returns false only when the engine refused it.
     */
    private suspend fun wipe(context: Context, contextId: String?): Boolean =
        withContext(Dispatchers.Main) {
            val runtime = GeckoRuntimeProvider.runtimeOrNull() ?: return@withContext true
            wipeWith(context, runtime, contextId)
        }

    private suspend fun wipeWith(context: Context, runtime: GeckoRuntime, contextId: String?): Boolean {
        val storage = runtime.storageController
        // A sandbox queued while everything is being cleared may already be past that clear.
        val covered = if (contextId == null) AppPrefs.getPendingSandboxClears(context) else emptySet()
        val wiped = runCatching {
            if (contextId == null) {
                storage.clearData(StorageController.ClearFlags.ALL).awaitVoid()
            } else {
                // Fire-and-forget in GeckoView: once dispatched, the engine carries it out.
                storage.clearDataForSessionContext(contextId)
            }
        }.onFailure { Log.w(TAG, "could not clear sandbox ${contextId ?: "(all)"}", it) }.isSuccess
        if (wiped) {
            if (contextId == null) {
                AppPrefs.forgetSandboxClearAll(context, covered)
            } else {
                AppPrefs.forgetPendingSandboxClear(context, contextId)
            }
        }
        return wiped
    }
}
