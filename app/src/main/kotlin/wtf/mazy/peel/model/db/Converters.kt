package wtf.mazy.peel.model.db

import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.WebAppGroupSurrogate
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.model.WebAppSurrogate

private const val BYPASS_SEPARATOR = "\n"

fun WebApp.toEntity(): WebAppEntity =
    WebAppEntity(
        uuid = uuid,
        baseUrl = baseUrl,
        title = title,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )

fun WebApp.toSurrogate(): WebAppSurrogate =
    WebAppSurrogate(
        baseUrl = baseUrl,
        uuid = uuid,
        title = title,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )

fun WebAppEntity.toDomain(): WebApp =
    webApp(
        uuid,
        baseUrl,
        title,
        isUseContainer,
        isEphemeralSandbox,
        proxyUuid,
        order,
        groupUuid,
        settings
    )

fun WebAppSurrogate.toDomain(overrideUuid: String = uuid): WebApp =
    webApp(
        overrideUuid,
        baseUrl,
        title,
        isUseContainer,
        isEphemeralSandbox,
        proxyUuid,
        order,
        groupUuid,
        settings
    )

private fun webApp(
    uuid: String,
    baseUrl: String,
    title: String,
    isUseContainer: Boolean,
    isEphemeralSandbox: Boolean,
    proxyUuid: String?,
    order: Int,
    groupUuid: String?,
    settings: WebAppSettings,
): WebApp = WebApp(
    baseUrl = baseUrl,
    uuid = uuid,
    title = title,
    isUseContainer = isUseContainer,
    isEphemeralSandbox = isEphemeralSandbox,
    proxyUuid = proxyUuid,
    order = order,
    groupUuid = groupUuid,
    settings = settings,
)

fun WebAppGroup.toEntity(): WebAppGroupEntity =
    WebAppGroupEntity(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        settings = settings,
    )

fun WebAppGroup.toSurrogate(): WebAppGroupSurrogate =
    WebAppGroupSurrogate(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        settings = settings,
    )

fun WebAppGroupEntity.toDomain(): WebAppGroup =
    WebAppGroup(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        settings = settings,
    )

fun WebAppGroupSurrogate.toDomain(): WebAppGroup =
    WebAppGroup(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
        settings = settings,
    )

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
