package wtf.mazy.peel.push

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

object NotificationClickCoordinator {

    class PendingClick internal constructor(val webAppUuid: String?) {
        internal val recordedAt = SystemClock.elapsedRealtime()
        internal val handled = CompletableDeferred<Unit>()
    }

    class Claim internal constructor(val webAppUuid: String?, val alreadyRouted: Boolean)

    @Volatile
    private var pending: PendingClick? = null

    fun record(webAppUuid: String?): PendingClick =
        PendingClick(webAppUuid).also { pending = it }

    fun claim(): Claim? {
        val slot = pending ?: return null
        pending = null
        if (SystemClock.elapsedRealtime() - slot.recordedAt > MAX_AGE_MS) return null
        return Claim(slot.webAppUuid, alreadyRouted = !slot.handled.complete(Unit))
    }

    suspend fun serviceWorkerWillNavigate(slot: PendingClick): Boolean {
        if (withTimeoutOrNull(GRACE_MS) { slot.handled.await() } != null) return true
        return !slot.handled.complete(Unit)
    }

    private const val GRACE_MS = 2_500L
    private const val MAX_AGE_MS = 15_000L
}
