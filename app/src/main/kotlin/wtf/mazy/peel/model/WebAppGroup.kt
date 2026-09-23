package wtf.mazy.peel.model

import java.util.Objects
import java.util.UUID

data class WebAppGroup(
    override val uuid: String = UUID.randomUUID().toString(),
    override val title: String = "",
    val order: Int = 0,
    override val isUseContainer: Boolean = false,
    override val isEphemeralSandbox: Boolean = false,
    override val proxyUuid: String? = null,
    val settings: WebAppSettings = WebAppSettings(),
) : IconOwner, SandboxOwner<WebAppGroup> {

    override fun withSandbox(
        isUseContainer: Boolean,
        isEphemeralSandbox: Boolean,
        proxyUuid: String?,
    ): WebAppGroup = copy(
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
    )

    val contentFingerprint: Int
        get() = Objects.hash(title, isUseContainer, isEphemeralSandbox, proxyUuid, iconStamp)
}
