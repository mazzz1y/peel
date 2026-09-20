package wtf.mazy.peel.browser

import wtf.mazy.peel.util.normalizedHost

class SessionPermissionMemory {

    private val page = mutableMapOf<Pair<String, Int>, Boolean>()
    private val session = mutableMapOf<Pair<String, Int>, Boolean>()
    private val pendingAsks = mutableMapOf<Pair<String, Int>, MutableList<(Boolean) -> Unit>>()

    fun remembered(origin: String, key: Int): Boolean? {
        val id = keyFor(origin, key) ?: return null
        return session[id] ?: page[id]
    }

    fun remember(origin: String, key: Int, granted: Boolean, forSession: Boolean) {
        val id = keyFor(origin, key) ?: return
        if (forSession) session[id] = granted else page[id] = granted
    }

    fun forget(origin: String, key: Int) {
        val id = keyFor(origin, key) ?: return
        session.remove(id)
        page.remove(id)
    }

    // Websites that repeatedly re-request the same permission (e.g. calling
    // getCurrentPosition in a loop) would otherwise stack a native dialog per call.
    fun joinAsk(origin: String, key: Int, onResult: (Boolean) -> Unit): Boolean {
        val id = keyFor(origin, key) ?: return false
        val queued = pendingAsks[id]
        if (queued != null) {
            queued.add(onResult)
            return true
        }
        pendingAsks[id] = mutableListOf(onResult)
        return false
    }

    // Returns false when there was nothing to resolve (unkeyable origin), so the
    // caller can fall back to answering its own request directly.
    fun resolveAsk(origin: String, key: Int, granted: Boolean): Boolean {
        val id = keyFor(origin, key) ?: return false
        val callbacks = pendingAsks.remove(id) ?: return false
        callbacks.forEach { it(granted) }
        return true
    }

    fun clearPage() {
        page.clear()
    }

    fun clearSession() {
        page.clear()
        session.clear()
    }

    private fun keyFor(origin: String, key: Int): Pair<String, Int>? =
        origin.normalizedHost()?.let { it to key }
}
