package wtf.mazy.peel.gecko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.StorageController
import wtf.mazy.peel.gecko.GeckoRuntimeProvider.awaitNullable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Peel's policy for Gecko's content permission store.
 *
 * Permissions are decided from Peel's own settings plus per-session memory, so Gecko's store is a
 * side effect rather than state. Gecko persists whatever the permission delegate returns with
 * `EXPIRE_NEVER` and only private-mode sessions get `EXPIRE_SESSION`, so a stored decision would
 * outlive the session it was made for. `VALUE_PROMPT` denies the request just like `VALUE_DENY`
 * while leaving the store neutral, which is why [valueFor] hands it out for every type Peel does
 * not deliberately remember.
 *
 * Entries that do get written are cleaned up by [requestSweep] rather than
 * `ClearFlags.PERMISSIONS`, which targets a host rather than a permission type and would take out
 * unrelated entries the host happens to hold.
 *
 * Two groups are exempt:
 * - notifications, the one type Peel keeps on purpose, because push subscriptions and the
 *   notification site list have to outlive the session that created them;
 * - storage access and tracking protection, which Gecko writes and expires itself --
 *   `setPermission` rewrites the storage-access key into `3rdPartyFrameStorage^…` and refuses
 *   `VALUE_PROMPT` for tracking outright.
 */
object ContentPermissionStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val peelOwned = setOf(PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION)

    private val geckoOwned = setOf(
        PermissionDelegate.PERMISSION_STORAGE_ACCESS,
        PermissionDelegate.PERMISSION_TRACKING,
    )

    private val pending = AtomicBoolean(true)

    private val sweeping = Mutex()

    /** The neutral state: refuses the request at hand and leaves nothing behind. */
    const val UNDECIDED = ContentPermission.VALUE_PROMPT

    /** The delegate reply carrying [granted] for [permission] without persisting a refusal. */
    fun valueFor(permission: Int, granted: Boolean): Int {
        if (!granted) {
            return if (permission in peelOwned) ContentPermission.VALUE_DENY else UNDECIDED
        }
        if (isSweepable(permission)) pending.set(true)
        return ContentPermission.VALUE_ALLOW
    }

    /**
     * Clears grants this process handed out, plus anything left by earlier ones.
     *
     * Starts out armed so every process sweeps once, which covers grants lost to a kill and entries
     * from versions that persisted refusals; afterwards only an actual grant arms it again. Both
     * checks are cheap enough for the session setup path, and a dead runtime is simply skipped
     * because creating one here would be far more expensive than the sweep it enables.
     */
    fun requestSweep(context: Context) {
        if (GeckoRuntimeProvider.runtimeOrNull() == null) return
        if (!pending.compareAndSet(true, false)) return
        val appContext = context.applicationContext
        scope.launch { sweep(appContext) }
    }

    suspend fun neutralize(context: Context, permissions: List<ContentPermission>) {
        if (permissions.isEmpty()) return
        onStorage(context) { storage ->
            permissions.forEach { storage.setPermission(it, UNDECIDED) }
        }
    }

    suspend fun all(context: Context): List<ContentPermission> =
        query(context) { it.allPermissions }

    suspend fun forOrigin(
        context: Context,
        origin: String,
        contextId: String?,
    ): List<ContentPermission> =
        query(context) { it.getPermissions(origin, contextId, false) }

    private suspend fun query(
        context: Context,
        request: (StorageController) -> GeckoResult<List<ContentPermission>>,
    ): List<ContentPermission> = onStorage(context) { storage ->
        runCatching { request(storage).awaitNullable() }.getOrNull().orEmpty()
    }

    private suspend fun <T> onStorage(
        context: Context,
        block: suspend (StorageController) -> T,
    ): T = withContext(Dispatchers.Main) {
        block(GeckoRuntimeProvider.getRuntime(context).storageController)
    }

    private suspend fun sweep(context: Context) = sweeping.withLock {
        neutralize(context, all(context).filter(::isLeftover))
    }

    private fun isLeftover(perm: ContentPermission): Boolean =
        perm.value != UNDECIDED &&
                !perm.privateMode &&
                isSweepable(perm.permission) &&
                isWebOrigin(perm.uri)

    private fun isSweepable(permission: Int): Boolean =
        permission !in peelOwned && permission !in geckoOwned

    private fun isWebOrigin(uri: String): Boolean =
        uri.startsWith("https://") || uri.startsWith("http://")
}
