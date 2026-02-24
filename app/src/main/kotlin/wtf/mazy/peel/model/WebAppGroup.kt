package wtf.mazy.peel.model

import java.util.UUID

data class WebAppGroup(
    override val uuid: String = UUID.randomUUID().toString(),
    override var title: String = "",
    var order: Int = 0,
    override var isUseContainer: Boolean = false,
    override var isEphemeralSandbox: Boolean = false,
    var settings: WebAppSettings = WebAppSettings(),
) : IconOwner, SandboxOwner {
    constructor(
        other: WebAppGroup
    ) : this(
        uuid = other.uuid,
        title = other.title,
        order = other.order,
        isUseContainer = other.isUseContainer,
        isEphemeralSandbox = other.isEphemeralSandbox,
        settings = other.settings.copy(),
    )
}
