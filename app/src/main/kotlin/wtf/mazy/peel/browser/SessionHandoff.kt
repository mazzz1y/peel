package wtf.mazy.peel.browser

import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.atomic.AtomicLong

/** Hands a session, and what to do once its host closes, to the activity that will display it. */
object SessionHandoff {

    class HandedSession(val session: GeckoSession, val onClose: (() -> Unit)?)

    private val pending = mutableMapOf<String, HandedSession>()
    private val counter = AtomicLong()

    fun put(session: GeckoSession, onClose: (() -> Unit)? = null): String {
        val key = counter.incrementAndGet().toString()
        synchronized(pending) { pending[key] = HandedSession(session, onClose) }
        return key
    }

    fun take(key: String): HandedSession? = synchronized(pending) { pending.remove(key) }
}
