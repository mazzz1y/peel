package wtf.mazy.peel.model.db

import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.model.WebAppGroupSurrogate
import wtf.mazy.peel.model.WebAppSurrogate

fun WebApp.toEntity(): WebAppEntity =
    WebAppEntity(
        uuid = uuid,
        baseUrl = baseUrl,
        title = title,
        isActiveEntry = isActiveEntry,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )

fun WebApp.toSurrogate(): WebAppSurrogate =
    WebAppSurrogate(
        baseUrl = baseUrl,
        uuid = uuid,
        title = title,
        isActiveEntry = isActiveEntry,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        order = order,
        groupUuid = groupUuid,
        settings = settings,
    )

fun WebAppEntity.toDomain(): WebApp {
    val webapp = WebApp(baseUrl, uuid)
    webapp.title = title
    webapp.isActiveEntry = isActiveEntry
    webapp.isUseContainer = isUseContainer
    webapp.isEphemeralSandbox = isEphemeralSandbox
    webapp.order = order
    webapp.groupUuid = groupUuid
    webapp.settings = settings
    return webapp
}

fun WebAppSurrogate.toDomain(): WebApp {
    val webapp = WebApp(baseUrl, uuid)
    webapp.title = title
    webapp.isActiveEntry = isActiveEntry
    webapp.isUseContainer = isUseContainer
    webapp.isEphemeralSandbox = isEphemeralSandbox
    webapp.order = order
    webapp.groupUuid = groupUuid
    webapp.settings = settings
    return webapp
}

fun WebAppGroup.toEntity(): WebAppGroupEntity =
    WebAppGroupEntity(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        settings = settings,
    )

fun WebAppGroup.toSurrogate(): WebAppGroupSurrogate =
    WebAppGroupSurrogate(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        settings = settings,
    )

fun WebAppGroupEntity.toDomain(): WebAppGroup =
    WebAppGroup(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        settings = settings,
    )

fun WebAppGroupSurrogate.toDomain(): WebAppGroup =
    WebAppGroup(
        uuid = uuid,
        title = title,
        order = order,
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        settings = settings,
    )
