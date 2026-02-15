package wtf.mazy.peel.model

import kotlinx.serialization.Serializable

@Serializable
data class WebAppGroupSurrogate(
    val uuid: String,
    val title: String = "",
    val order: Int = 0,
    val isUseContainer: Boolean = false,
    val isEphemeralSandbox: Boolean = false,
    val settings: WebAppSettings = WebAppSettings(),
)
