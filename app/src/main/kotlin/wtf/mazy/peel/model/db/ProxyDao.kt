package wtf.mazy.peel.model.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface ProxyDao {

    @Query("SELECT * FROM proxies")
    fun getAll(): List<ProxyEntity>

    @Query("SELECT * FROM proxies WHERE uuid = :uuid LIMIT 1")
    fun getByUuid(uuid: String): ProxyEntity?

    @Upsert
    fun upsert(entity: ProxyEntity)

    @Upsert
    fun upsertAll(entities: List<ProxyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<ProxyEntity>)

    @Query("DELETE FROM proxies WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("DELETE FROM proxies")
    fun deleteAll()

    @Transaction
    fun replaceAll(entities: List<ProxyEntity>) {
        deleteAll()
        insertAll(entities)
    }
}
