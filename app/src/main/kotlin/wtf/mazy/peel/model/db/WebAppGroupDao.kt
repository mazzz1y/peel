package wtf.mazy.peel.model.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface WebAppGroupDao {

    @Query("SELECT * FROM webapp_groups ORDER BY `order`")
    fun getAllGroups(): List<WebAppGroupEntity>

    @Upsert fun upsert(group: WebAppGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<WebAppGroupEntity>)

    @Query("DELETE FROM webapp_groups WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("DELETE FROM webapp_groups")
    fun deleteAll()

    @Transaction
    fun replaceAll(entities: List<WebAppGroupEntity>) {
        deleteAll()
        insertAll(entities)
    }
}
