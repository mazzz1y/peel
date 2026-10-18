package wtf.mazy.peel.gecko

import androidx.annotation.MainThread

@MainThread
class SandboxUsage(private val onIdle: (contextId: String) -> Unit) {

    private val users = mutableMapOf<String, Int>()

    fun isInUse(contextId: String): Boolean = contextId in users

    fun acquire(contextId: String) {
        users[contextId] = users.getOrElse(contextId) { 0 } + 1
    }

    fun release(contextId: String) {
        val remaining = users.getValue(contextId) - 1
        if (remaining > 0) {
            users[contextId] = remaining
        } else {
            users.remove(contextId)
            onIdle(contextId)
        }
    }
}
