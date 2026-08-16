package wtf.mazy.peel.browser

import wtf.mazy.peel.util.normalizedHost

class SessionPermissionMemory {

    private val page = mutableMapOf<Pair<String, Int>, Boolean>()
    private val session = mutableMapOf<Pair<String, Int>, Boolean>()

    fun remembered(origin: String, key: Int): Boolean? {
        val id = keyFor(origin, key) ?: return null
        return session[id] ?: page[id]
    }

    fun remember(origin: String, key: Int, granted: Boolean, forSession: Boolean) {
        val id = keyFor(origin, key) ?: return
        if (forSession) session[id] = granted else page[id] = granted
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
