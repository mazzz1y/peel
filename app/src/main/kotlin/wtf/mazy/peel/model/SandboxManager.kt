package wtf.mazy.peel.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.gecko.GeckoRuntimeProvider
import wtf.mazy.peel.util.App

object SandboxManager {

    private const val PREFS = "ephemeral_sandboxes"
    private const val KEY_PENDING = "pending"

    private fun prefs() =
        App.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markEphemeral(contextId: String) {
        val pending = prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty()
        prefs().edit().putStringSet(KEY_PENDING, pending + contextId).apply()
    }

    private fun unmarkEphemeral(contextId: String) {
        val pending = prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty()
        if (contextId !in pending) return
        prefs().edit().putStringSet(KEY_PENDING, pending - contextId).apply()
    }

    fun clearSandboxData(context: Context, contextId: String): Boolean {
        return try {
            val runtime = GeckoRuntimeProvider.getRuntime(context)
            runtime.storageController.clearDataForSessionContext(contextId)
            unmarkEphemeral(contextId)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sweepOrphanedSandboxes(context: Context) {
        val orphaned = prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty()
        if (orphaned.isEmpty()) return
        withContext(Dispatchers.Main) {
            val runtime = GeckoRuntimeProvider.getRuntime(context)
            orphaned.forEach { contextId ->
                try {
                    runtime.storageController.clearDataForSessionContext(contextId)
                } catch (_: Exception) {
                }
            }
        }
        val remaining = prefs().getStringSet(KEY_PENDING, emptySet()).orEmpty() - orphaned
        prefs().edit().putStringSet(KEY_PENDING, remaining).apply()
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
