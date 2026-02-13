package wtf.mazy.peel.model.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import wtf.mazy.peel.model.WebAppSettings

@Entity(tableName = "webapps")
data class WebAppEntity(
    @PrimaryKey val uuid: String,
    val baseUrl: String,
    val title: String,
    val isActiveEntry: Boolean,
    val isUseContainer: Boolean,
    val isEphemeralSandbox: Boolean,
    val order: Int,
    @Embedded val settings: WebAppSettings = WebAppSettings(),
)
