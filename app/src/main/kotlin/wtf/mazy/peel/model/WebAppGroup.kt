package wtf.mazy.peel.model

import java.util.UUID

data class WebAppGroup(
    val uuid: String = UUID.randomUUID().toString(),
    var title: String = "",
    var order: Int = 0,
    var isUseContainer: Boolean = false,
    var isEphemeralSandbox: Boolean = false,
    var settings: WebAppSettings = WebAppSettings(),
) {
    constructor(other: WebAppGroup) : this(
        uuid = other.uuid,
        title = other.title,
        order = other.order,
        isUseContainer = other.isUseContainer,
        isEphemeralSandbox = other.isEphemeralSandbox,
        settings = other.settings.copy(),
    )
}
