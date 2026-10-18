package wtf.mazy.peel.model

object ContainerRoutes {

    fun of(state: DataState, transientWebApps: List<WebApp>): Map<String, ProxyRoute> {
        val proxyUuids = state.proxyUuids
        val groups = state.groups.associateBy { it.uuid }
        val sandboxes = state.groups.map(::effectiveSandbox) +
                (state.webApps + transientWebApps).map { app ->
                    effectiveSandbox(app, app.groupUuid?.let(groups::get))
                }

        return buildMap {
            for (sandbox in sandboxes) {
                if (sandbox !is EffectiveSandbox.Own) continue
                val route = sandbox.route
                if (route is ProxyRoute.Via && route.proxyUuid !in proxyUuids) continue
                put(sandbox.contextId, route)
            }
        }
    }
}
