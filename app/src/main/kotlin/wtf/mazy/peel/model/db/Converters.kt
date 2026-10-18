package wtf.mazy.peel.model.db

import wtf.mazy.peel.model.Isolation
import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.ProxyChoice
import wtf.mazy.peel.model.ProxyRoute
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.WebAppGroupSurrogate
import wtf.mazy.peel.model.WebAppSurrogate

private const val BYPASS_SEPARATOR = "\n"

fun WebApp.toEntity(): WebAppEntity {
    val stored = StoredIsolation.of(isolation, ::webAppProxyToStored)
    return WebAppEntity(
        uuid = uuid,
        baseUrl = baseUrl,
        title = title,
        isUseContainer = stored.isUseContainer,
        isEphemeralSandbox = stored.isEphemeralSandbox,
        proxyUuid = stored.proxyUuid,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )
}

fun WebApp.toSurrogate(): WebAppSurrogate {
    val stored = StoredIsolation.of(isolation, ::webAppProxyToStored)
    return WebAppSurrogate(
        baseUrl = baseUrl,
        uuid = uuid,
        title = title,
        isUseContainer = stored.isUseContainer,
        isEphemeralSandbox = stored.isEphemeralSandbox,
        proxyUuid = stored.proxyUuid,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )
}

fun WebAppEntity.toDomain(): WebApp = WebApp(
    baseUrl = baseUrl,
    uuid = uuid,
    title = title,
    isolation = StoredIsolation(isUseContainer, isEphemeralSandbox, proxyUuid)
        .toIsolation(::webAppProxyFromStored),
    order = order,
    groupUuid = groupUuid,
    settings = settings,
)

fun WebAppSurrogate.toDomain(overrideUuid: String = uuid): WebApp = WebApp(
    baseUrl = baseUrl,
    uuid = overrideUuid,
    title = title,
    isolation = StoredIsolation(isUseContainer, isEphemeralSandbox, proxyUuid)
        .toIsolation(::webAppProxyFromStored),
    order = order,
    groupUuid = groupUuid,
    settings = settings,
)

fun WebAppGroup.toEntity(): WebAppGroupEntity {
    val stored = StoredIsolation.of(isolation, ::groupProxyToStored)
    return WebAppGroupEntity(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = stored.isUseContainer,
        isEphemeralSandbox = stored.isEphemeralSandbox,
        proxyUuid = stored.proxyUuid,
        settings = settings,
    )
}

fun WebAppGroup.toSurrogate(): WebAppGroupSurrogate {
    val stored = StoredIsolation.of(isolation, ::groupProxyToStored)
    return WebAppGroupSurrogate(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = stored.isUseContainer,
        isEphemeralSandbox = stored.isEphemeralSandbox,
        proxyUuid = stored.proxyUuid,
        settings = settings,
    )
}

fun WebAppGroupEntity.toDomain(): WebAppGroup = WebAppGroup(
    uuid = uuid,
    title = title,
    order = order,
    isolation = StoredIsolation(isUseContainer, isEphemeralSandbox, proxyUuid)
        .toIsolation(::groupProxyFromStored),
    settings = settings,
)

fun WebAppGroupSurrogate.toDomain(): WebAppGroup = WebAppGroup(
    uuid = uuid,
    title = title,
    order = order,
    isolation = StoredIsolation(isUseContainer, isEphemeralSandbox, proxyUuid)
        .toIsolation(::groupProxyFromStored),
    settings = settings,
)

private data class StoredIsolation(
    val isUseContainer: Boolean,
    val isEphemeralSandbox: Boolean,
    val proxyUuid: String?,
) {
    fun <P : ProxyChoice> toIsolation(proxyFromStored: (String?) -> P): Isolation<P> =
        if (isUseContainer) Isolation.Sandboxed(isEphemeralSandbox, proxyFromStored(proxyUuid))
        else Isolation.Shared

    companion object {
        fun <P : ProxyChoice> of(isolation: Isolation<P>, proxyToStored: (P) -> String?) =
            when (isolation) {
                Isolation.Shared -> StoredIsolation(
                    isUseContainer = false,
                    isEphemeralSandbox = false,
                    proxyUuid = null,
                )
                is Isolation.Sandboxed ->
                    StoredIsolation(
                        isUseContainer = true,
                        isEphemeralSandbox = isolation.ephemeral,
                        proxyUuid = proxyToStored(isolation.proxy),
                    )
            }
    }
}

// Web apps store an explicit "no proxy" under a marker so that null keeps its pre-existing
// meaning (inherit). Imported proxy uuids must be canonical, so the marker cannot collide.
private const val STORED_DIRECT = "direct"

private fun webAppProxyToStored(proxy: ProxyChoice): String? = when (proxy) {
    ProxyChoice.Inherit -> null
    ProxyRoute.Direct -> STORED_DIRECT
    is ProxyRoute.Via -> proxy.proxyUuid
}

private fun webAppProxyFromStored(stored: String?): ProxyChoice = when (stored) {
    null -> ProxyChoice.Inherit
    STORED_DIRECT -> ProxyRoute.Direct
    else -> ProxyRoute.Via(stored)
}

private fun groupProxyToStored(proxy: ProxyRoute): String? = when (proxy) {
    ProxyRoute.Direct -> null
    is ProxyRoute.Via -> proxy.proxyUuid
}

private fun groupProxyFromStored(stored: String?): ProxyRoute = when (stored) {
    null, STORED_DIRECT -> ProxyRoute.Direct
    else -> ProxyRoute.Via(stored)
}

fun Proxy.toEntity(): ProxyEntity = ProxyEntity(
    uuid = uuid,
    name = name,
    type = type,
    host = host,
    port = port,
    username = username,
    password = password,
    remoteDns = remoteDns,
    bypassList = bypassList.joinToString(BYPASS_SEPARATOR),
)

fun ProxyEntity.toDomain(): Proxy = Proxy(
    uuid = uuid,
    name = name,
    type = type,
    host = host,
    port = port,
    username = username,
    password = password,
    remoteDns = remoteDns,
    bypassList = if (bypassList.isEmpty()) emptyList()
    else bypassList.split(BYPASS_SEPARATOR).filter { it.isNotEmpty() },
)
