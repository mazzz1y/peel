package wtf.mazy.peel.model.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import wtf.mazy.peel.model.WebAppSettings

@Entity(tableName = "webapp_groups")
data class WebAppGroupEntity(
    @PrimaryKey val uuid: String,
    val title: String,
    val order: Int = 0,
    val isUseContainer: Boolean = false,
    val isEphemeralSandbox: Boolean = false,
    @Embedded val settings: WebAppSettings = WebAppSettings(),
)
