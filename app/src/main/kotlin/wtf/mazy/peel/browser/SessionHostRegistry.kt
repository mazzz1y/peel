package wtf.mazy.peel.browser

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks every live session host so callers can finish hosts by owner web app, by sandbox
 * context, or all browsers at once, and wait until the finished hosts have actually gone.
 */
object SessionHostRegistry {

    private const val CLOSE_TIMEOUT_MS = 2_000L

    private class Entry(val ownerUuid: String?, val isBrowser: Boolean) {
        var contextId: String? = null
    }

    private class CloseWaiter(val contextId: String?, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private val hosts = mutableMapOf<Activity, Entry>()
    private val closeWaiters = mutableListOf<CloseWaiter>()

    fun register(host: Activity, ownerUuid: String?, isBrowser: Boolean) {
        synchronized(lock) { hosts[host] = Entry(ownerUuid, isBrowser) }
    }

    fun bindContext(host: Activity, contextId: String?) {
        val settled = synchronized(lock) {
            hosts[host]?.contextId = contextId
            takeSettledWaiters()
        }
        settled.forEach { it.done.complete(Unit) }
    }

    fun unregister(host: Activity) {
        val settled = synchronized(lock) {
            if (hosts.remove(host) == null) return
            takeSettledWaiters()
        }
        settled.forEach { it.done.complete(Unit) }
    }

    val hasLiveBrowsers: Boolean
        get() = synchronized(lock) { hosts.values.any { it.isBrowser } }

    fun finishBrowsers() = finish { it.isBrowser }

    fun finishPopupsOwnedBy(ownerUuid: String) = finish { !it.isBrowser && it.ownerUuid == ownerUuid }

    private fun finish(predicate: (Entry) -> Boolean) {
        val targets = synchronized(lock) { hosts.filterValues(predicate).keys.toList() }
        targets.forEach { it.finish() }
    }

    suspend fun closeAllSessions() = close(null)

    suspend fun closeSessionsFor(contextId: String) = close(contextId)

    private suspend fun close(contextId: String?) {
        val waiter = CloseWaiter(contextId, CompletableDeferred())
        val targets = synchronized(lock) {
            val matching = hosts
                .filterValues { contextId == null || it.contextId == contextId }
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
            hosts.none { waiter.contextId == null || it.value.contextId == waiter.contextId }
        }
        closeWaiters.removeAll(settled)
        return settled
    }
}
