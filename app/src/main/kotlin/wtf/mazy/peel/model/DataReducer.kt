package wtf.mazy.peel.model

object DataReducer {

    data class StateMutation(
        val websites: List<WebApp>? = null,
        val groups: List<WebAppGroup>? = null,
        val defaultSettings: WebApp? = null,
        val emit: Boolean = true,
    )

    fun apply(state: DataState, mutation: StateMutation): DataState {
        return DataState(
            websites = mutation.websites ?: state.websites,
            groups = mutation.groups ?: state.groups,
            defaultSettings = mutation.defaultSettings ?: state.defaultSettings,
        )
    }

    fun withLoadedData(
        websites: List<WebApp>,
        groups: List<WebAppGroup>,
        defaultSettings: WebApp,
        emit: Boolean = true,
    ): StateMutation {
        return StateMutation(
            websites = websites.map { WebApp(it) },
            groups = groups.map { WebAppGroup(it) },
            defaultSettings = WebApp(defaultSettings),
            emit = emit,
        )
    }

    fun withWebsites(websites: List<WebApp>, emit: Boolean = true): StateMutation {
        return StateMutation(
            websites = websites.map { WebApp(it) },
            emit = emit,
        )
    }

    fun withGroups(groups: List<WebAppGroup>, emit: Boolean = true): StateMutation {
        return StateMutation(
            groups = groups.map { WebAppGroup(it) },
            emit = emit,
        )
    }

    fun withDefaultSettings(defaultSettings: WebApp, emit: Boolean = true): StateMutation {
        return StateMutation(
            defaultSettings = WebApp(defaultSettings),
            emit = emit,
        )
    }

    fun replacingWebsite(state: DataState, site: WebApp, emit: Boolean = true): StateMutation {
        return StateMutation(
            websites = state.websites.map { current ->
                if (current.uuid == site.uuid) WebApp(site) else WebApp(current)
            },
            emit = emit,
        )
    }

    fun movingWebsitesToGroup(
        state: DataState,
        uuids: Set<String>,
        groupUuid: String?,
        emit: Boolean = true,
    ): StateMutation {
        return StateMutation(
            websites = state.websites.map { site ->
                if (site.uuid in uuids) WebApp(site).apply { this.groupUuid = groupUuid } else WebApp(site)
            },
            emit = emit,
        )
    }

    fun reorderingWebsites(state: DataState, orderedUuids: List<String>, emit: Boolean = true): StateMutation {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return StateMutation(
            websites = state.websites.map { site ->
                val targetOrder = orderMap[site.uuid]
                if (targetOrder != null) WebApp(site).apply { order = targetOrder } else WebApp(site)
            },
            emit = emit,
        )
    }

    fun markingWebsitesActive(
        state: DataState,
        uuids: Set<String>,
        isActive: Boolean,
        emit: Boolean = true,
    ): StateMutation {
        return StateMutation(
            websites = state.websites.map { site ->
                if (site.uuid in uuids) WebApp(site).apply { isActiveEntry = isActive } else WebApp(site)
            },
            emit = emit,
        )
    }

    fun replacingGroup(state: DataState, group: WebAppGroup, emit: Boolean = true): StateMutation {
        return StateMutation(
            groups = state.groups.map { current ->
                if (current.uuid == group.uuid) WebAppGroup(group) else WebAppGroup(current)
            },
            emit = emit,
        )
    }

    fun reorderingGroups(state: DataState, orderedUuids: List<String>, emit: Boolean = true): StateMutation {
        val orderMap = orderedUuids.withIndex().associate { it.value to it.index }
        return StateMutation(
            groups = state.groups.map { group ->
                val targetOrder = orderMap[group.uuid]
                if (targetOrder != null) WebAppGroup(group).apply { order = targetOrder } else WebAppGroup(group)
            },
            emit = emit,
        )
    }
}
