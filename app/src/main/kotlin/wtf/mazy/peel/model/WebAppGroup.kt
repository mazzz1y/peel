package wtf.mazy.peel.model

import java.util.Objects
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
        settings = other.settings.deepCopy(),
    )

    override val iconStamp: Long = if (iconFile.exists()) iconFile.lastModified() else 0L

    val contentFingerprint: Int
        get() = Objects.hash(title, isUseContainer, isEphemeralSandbox, iconStamp)
}
