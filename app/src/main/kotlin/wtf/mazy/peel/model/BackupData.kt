package wtf.mazy.peel.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: String,
    val websites: List<WebAppSurrogate>,
    val globalSettings: WebAppSettings,
    val groups: List<WebAppGroupSurrogate> = emptyList(),
)
