package wtf.mazy.peel.model.db

import wtf.mazy.peel.model.WebApp
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
        settings = settings,
    )

fun WebAppEntity.toDomain(): WebApp {
    val webapp = WebApp(baseUrl, uuid)
    webapp.title = title
    webapp.isActiveEntry = isActiveEntry
    webapp.isUseContainer = isUseContainer
    webapp.isEphemeralSandbox = isEphemeralSandbox
    webapp.order = order
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
    webapp.settings = settings
    return webapp
}
