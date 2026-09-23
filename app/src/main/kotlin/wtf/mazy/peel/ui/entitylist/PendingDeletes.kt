package wtf.mazy.peel.ui.entitylist

import java.util.concurrent.ConcurrentHashMap

object PendingDeletes {
    val webApps: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val groups: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
