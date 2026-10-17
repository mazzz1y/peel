package wtf.mazy.peel.model

sealed interface EffectiveSandbox {
    val route: ProxyRoute

    data object Private : EffectiveSandbox {
        override val route: ProxyRoute
            get() = ProxyRoute.Direct
    }

    data class Shared(
        val groupContextId: String?,
        val ephemeral: Boolean,
        override val route: ProxyRoute,
    ) : EffectiveSandbox

    data class Own(
        val contextId: String,
        val ephemeral: Boolean,
        override val route: ProxyRoute,
    ) : EffectiveSandbox
}

val EffectiveSandbox.contextId: String?
    get() = when (this) {
        EffectiveSandbox.Private -> null
        is EffectiveSandbox.Shared -> groupContextId
        is EffectiveSandbox.Own -> contextId
    }

val EffectiveSandbox.ephemeralContextId: String?
    get() = when (this) {
        EffectiveSandbox.Private -> null
        is EffectiveSandbox.Shared -> groupContextId?.takeIf { ephemeral }
        is EffectiveSandbox.Own -> contextId.takeIf { ephemeral }
    }

fun effectiveSandbox(group: WebAppGroup): EffectiveSandbox =
    when (val isolation = group.isolation) {
        Isolation.Shared -> EffectiveSandbox.Shared(null, ephemeral = false, route = ProxyRoute.Direct)
        is Isolation.Sandboxed -> EffectiveSandbox.Own(group.uuid, isolation.ephemeral, isolation.proxy)
    }

fun effectiveSandbox(webApp: WebApp, group: WebAppGroup?): EffectiveSandbox {
    if (webApp.isPrivateSession) return EffectiveSandbox.Private
    val groupOwn = group?.let(::effectiveSandbox) as? EffectiveSandbox.Own
    val groupRoute = groupOwn?.route ?: ProxyRoute.Direct
    return when (val isolation = webApp.isolation) {
        Isolation.Shared -> EffectiveSandbox.Shared(
            groupContextId = groupOwn?.contextId,
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
