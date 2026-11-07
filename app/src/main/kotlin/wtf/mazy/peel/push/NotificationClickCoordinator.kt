package wtf.mazy.peel.push

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

object NotificationClickCoordinator {

    class PendingClick internal constructor(
        val webAppUuid: String?,
        val sandbox: NotificationClickLaunch.Sandbox?,
    ) {
        internal val recordedAt = SystemClock.elapsedRealtime()
        internal val handled = CompletableDeferred<Unit>()
    }

    class Claim internal constructor(
        val webAppUuid: String?,
        val sandbox: NotificationClickLaunch.Sandbox?,
        val alreadyRouted: Boolean,
    )

    @Volatile
    private var pending: PendingClick? = null

    fun record(webAppUuid: String?, sandbox: NotificationClickLaunch.Sandbox?): PendingClick =
        PendingClick(webAppUuid, sandbox).also { pending = it }

    fun claim(): Claim? {
        val slot = pending ?: return null
        pending = null
        if (SystemClock.elapsedRealtime() - slot.recordedAt > MAX_AGE_MS) return null
        return Claim(slot.webAppUuid, slot.sandbox, alreadyRouted = !slot.handled.complete(Unit))
    }

    suspend fun serviceWorkerWillNavigate(slot: PendingClick): Boolean {
        return withTimeoutOrNull(GRACE_MS.milliseconds) { slot.handled.await() } != null ||
                !slot.handled.complete(Unit)
    }

    private const val GRACE_MS = 2_500L
    private const val MAX_AGE_MS = 15_000L
}
