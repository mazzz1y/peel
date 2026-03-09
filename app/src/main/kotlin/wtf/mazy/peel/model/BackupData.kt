package wtf.mazy.peel.model

import kotlinx.serialization.Serializable
import wtf.mazy.peel.model.backup.BackupPolicy

@Serializable
data class BackupData(
    val version: String,
    val payloadType: String = BackupPolicy.PAYLOAD_FULL,
    val websites: List<WebAppSurrogate>,
    val globalSettings: WebAppSettings? = null,
    val groups: List<WebAppGroupSurrogate> = emptyList(),
)
