package wtf.mazy.peel.model

import kotlinx.serialization.Serializable

@Serializable
data class WebAppSurrogate(
    val baseUrl: String,
    val uuid: String,
    val title: String = "",
    val isActiveEntry: Boolean = true,
    val isUseContainer: Boolean = false,
    val isEphemeralSandbox: Boolean = false,
    val order: Int = 0,
    val settings: WebAppSettings = WebAppSettings(),
)
