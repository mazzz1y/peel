package wtf.mazy.peel.browser

import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.atomic.AtomicLong

object PopupSessionHolder {
    private val sessions = mutableMapOf<String, GeckoSession>()
    private val counter = AtomicLong()

    fun put(session: GeckoSession): String {
        val key = counter.incrementAndGet().toString()
        sessions[key] = session
        return key
    }

    fun take(key: String): GeckoSession? = sessions.remove(key)
}
