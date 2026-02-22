package wtf.mazy.peel.model.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SandboxSlotDao {

    @Query("SELECT webappUuid FROM sandbox_slots WHERE slotId = :slotId")
    fun getUuid(slotId: Int): String?

    @Query("SELECT * FROM sandbox_slots")
    fun getAll(): List<SandboxSlotEntity>

    @Upsert fun assign(slot: SandboxSlotEntity)

    @Query("DELETE FROM sandbox_slots WHERE slotId = :slotId") fun clear(slotId: Int)

    @Query("DELETE FROM sandbox_slots") fun clearAll()
}
