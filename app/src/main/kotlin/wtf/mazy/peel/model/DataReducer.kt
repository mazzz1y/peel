package wtf.mazy.peel.model

object DataReducer {

    data class StateMutation(
        val websites: List<WebApp>? = null,
        val groups: List<WebAppGroup>? = null,
        val defaultSettings: WebApp? = null,
        val proxies: List<Proxy>? = null,
    )

    fun apply(state: DataState, mutation: StateMutation): DataState {
        return DataState(
            websites = mutation.websites ?: state.websites,
            groups = mutation.groups ?: state.groups,
            defaultSettings = mutation.defaultSettings ?: state.defaultSettings,
            proxies = mutation.proxies ?: state.proxies,
        )
    }

    fun withLoadedData(
        websites: List<WebApp>,
        groups: List<WebAppGroup>,
        defaultSettings: WebApp,
        proxies: List<Proxy>,
    ): StateMutation {
        return StateMutation(
            websites = websites.map { WebApp(it) },
            groups = groups.map { WebAppGroup(it) },
            defaultSettings = WebApp(defaultSettings),
            proxies = proxies.map { it.copy() },
        )
    }

    fun withProxies(proxies: List<Proxy>): StateMutation {
        return StateMutation(
            proxies = proxies.map { it.copy() },
        )
    }

    fun withWebsites(websites: List<WebApp>): StateMutation {
        return StateMutation(
            websites = websites.map { WebApp(it) },
        )
    }

    fun withGroups(groups: List<WebAppGroup>): StateMutation {
        return StateMutation(
            groups = groups.map { WebAppGroup(it) },
        )
    }

    fun withDefaultSettings(defaultSettings: WebApp): StateMutation {
        return StateMutation(
            defaultSettings = WebApp(defaultSettings),
        )
    }

    fun replacingWebsite(state: DataState, site: WebApp): StateMutation {
        return StateMutation(
            websites = state.websites.map { current ->
                if (current.uuid == site.uuid) WebApp(site) else WebApp(current)
            },
        )
    }

    fun movingWebsitesToGroup(
        state: DataState,
        uuids: Set<String>,
        groupUuid: String?,
    ): StateMutation {
        // Order is scoped to a group, so a moved app has to be renumbered onto the end of the
        // destination; keeping its old value would collide with whatever already sits there.
        var nextOrder = state.websites
            .filter { it.uuid !in uuids && it.groupUuid == groupUuid }
            .maxOfOrNull { it.order }
            ?.plus(1) ?: 0
        val movedOrders = state.websites
            .filter { it.uuid in uuids }
            .sortedBy { it.order }
            .associate { it.uuid to nextOrder++ }
        return StateMutation(
            websites = state.websites.map { site ->
                if (site.uuid in uuids) WebApp(site).apply {
                    this.groupUuid = groupUuid
                    movedOrders[site.uuid]?.let { order = it }
                } else WebApp(site)
            },
        )
    }

    fun reorderingWebsites(
        state: DataState,
        orderedUuids: List<String>,
    ): StateMutation {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return StateMutation(
            websites = state.websites.map { site ->
                val targetOrder = orderMap[site.uuid]
                if (targetOrder != null) WebApp(site).apply {
                    order = targetOrder
                } else WebApp(site)
            },
        )
    }

    fun replacingGroup(state: DataState, group: WebAppGroup): StateMutation {
        return StateMutation(
            groups = state.groups.map { current ->
                if (current.uuid == group.uuid) WebAppGroup(group) else WebAppGroup(current)
            },
        )
    }

    fun reorderingGroups(
        state: DataState,
        orderedUuids: List<String>,
    ): StateMutation {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return StateMutation(
            groups = state.groups.map { group ->
                val targetOrder = orderMap[group.uuid]
                if (targetOrder != null) WebAppGroup(group).apply {
                    order = targetOrder
                } else WebAppGroup(group)
            },
        )
    }
}
