package wtf.mazy.peel.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sandbox_slots")
data class SandboxSlotEntity(@PrimaryKey val slotId: Int, val webappUuid: String)
