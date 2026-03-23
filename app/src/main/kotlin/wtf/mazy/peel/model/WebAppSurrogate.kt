package wtf.mazy.peel.model

import kotlinx.serialization.Serializable

@Serializable
data class WebAppSurrogate(
    val baseUrl: String,
    val uuid: String,
    val title: String = "",
    val isUseContainer: Boolean = false,
    val isEphemeralSandbox: Boolean = false,
    val order: Int = 0,
    val groupUuid: String? = null,
    val settings: WebAppSettings = WebAppSettings(),
)
