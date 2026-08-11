package wtf.mazy.peel.browser

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object SessionContextRegistry {

    private const val CLOSE_TIMEOUT_MS = 2_000L

    private val lock = Any()
    private val contextIds = mutableMapOf<Activity, String?>()
    private val closeWaiters = mutableListOf<CloseWaiter>()

    private class CloseWaiter(val contextId: String?, val done: CompletableDeferred<Unit>)

    fun register(activity: Activity, contextId: String?) {
        val settled = synchronized(lock) {
            contextIds[activity] = contextId
            takeSettledWaiters()
        }
        settled.forEach { it.done.complete(Unit) }
    }

    fun unregister(activity: Activity) {
        val settled = synchronized(lock) {
            if (!contextIds.containsKey(activity)) return
            contextIds.remove(activity)
            takeSettledWaiters()
        }
        settled.forEach { it.done.complete(Unit) }
    }

    suspend fun closeAllSessions() = close(null)

    suspend fun closeSessionsFor(contextId: String) = close(contextId)

    private suspend fun close(contextId: String?) {
        val waiter = CloseWaiter(contextId, CompletableDeferred())
        val targets = synchronized(lock) {
            val matching = contextIds
                .filterValues { contextId == null || it == contextId }
                .keys
                .toList()
            if (matching.isNotEmpty()) closeWaiters.add(waiter)
            matching
        }
        if (targets.isEmpty()) return

        try {
            withContext(Dispatchers.Main) { targets.forEach { it.finish() } }
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { waiter.done.await() }
        } finally {
            synchronized(lock) { closeWaiters.remove(waiter) }
        }
    }

    private fun takeSettledWaiters(): List<CloseWaiter> {
        val settled = closeWaiters.filter { waiter ->
            contextIds.none { waiter.contextId == null || it.value == waiter.contextId }
        }
        closeWaiters.removeAll(settled)
        return settled
    }
}
