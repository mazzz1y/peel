package wtf.mazy.peel.model

sealed interface EffectiveSandbox {
    val contextId: String?
    val route: ProxyRoute

    val ephemeralContextId: String?
        get() = when (this) {
            Private -> null
            is Shared -> contextId?.takeIf { ephemeral }
            is Own -> contextId.takeIf { ephemeral }
        }

    data object Private : EffectiveSandbox {
        override val contextId: String?
            get() = null
        override val route: ProxyRoute
            get() = ProxyRoute.Direct
    }

    data class Shared(
        override val contextId: String?,
        val ephemeral: Boolean,
        override val route: ProxyRoute,
    ) : EffectiveSandbox

    data class Own(
        override val contextId: String,
        val ephemeral: Boolean,
        override val route: ProxyRoute,
    ) : EffectiveSandbox
}

fun effectiveSandbox(group: WebAppGroup): EffectiveSandbox =
    when (val isolation = group.isolation) {
        Isolation.Shared -> EffectiveSandbox.Shared(contextId = null, ephemeral = false, route = ProxyRoute.Direct)
        is Isolation.Sandboxed -> EffectiveSandbox.Own(group.uuid, isolation.ephemeral, isolation.proxy)
    }

fun effectiveSandbox(webApp: WebApp, group: WebAppGroup?): EffectiveSandbox {
    if (webApp.isPrivateSession) return EffectiveSandbox.Private
    val groupOwn = group?.let(::effectiveSandbox) as? EffectiveSandbox.Own
    val groupRoute = groupOwn?.route ?: ProxyRoute.Direct
    return when (val isolation = webApp.isolation) {
        Isolation.Shared -> EffectiveSandbox.Shared(
            contextId = groupOwn?.contextId,
            ephemeral = groupOwn?.ephemeral == true,
            route = groupRoute,
        )
        is Isolation.Sandboxed -> EffectiveSandbox.Own(
            contextId = webApp.uuid,
            ephemeral = isolation.ephemeral,
            route = when (val proxy = isolation.proxy) {
                ProxyChoice.Inherit -> groupRoute
                is ProxyRoute -> proxy
            },
        )
    }
}
