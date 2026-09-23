package wtf.mazy.peel.model

object DataReducer {

    fun movingWebAppsToGroup(state: DataState, uuids: Set<String>, groupUuid: String?): DataState {
        // Order is scoped to a group, so a moved app has to be renumbered onto the end of the
        // destination; keeping its old value would collide with whatever already sits there.
        var nextOrder = state.webApps
            .filter { it.uuid !in uuids && it.groupUuid == groupUuid }
            .maxOfOrNull { it.order }
            ?.plus(1) ?: 0
        val movedOrders = state.webApps
            .filter { it.uuid in uuids }
            .sortedBy { it.order }
            .associate { it.uuid to nextOrder++ }
        return state.copy(
            webApps = state.webApps.map { webApp ->
                val order = movedOrders[webApp.uuid] ?: return@map webApp
                webApp.copy(groupUuid = groupUuid, order = order)
            },
        )
    }

    fun reorderingWebApps(state: DataState, orderedUuids: List<String>): DataState {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return state.copy(
            webApps = state.webApps.map { webApp ->
                orderMap[webApp.uuid]?.let { webApp.copy(order = it) } ?: webApp
            },
        )
    }

    fun reorderingGroups(state: DataState, orderedUuids: List<String>): DataState {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return state.copy(
            groups = state.groups.map { group ->
                orderMap[group.uuid]?.let { group.copy(order = it) } ?: group
            },
        )
    }
}
