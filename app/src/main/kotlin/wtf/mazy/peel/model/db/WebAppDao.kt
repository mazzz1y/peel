package wtf.mazy.peel.model.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import wtf.mazy.peel.util.Const

@Dao
interface WebAppDao {

    @Query("SELECT * FROM webapps WHERE uuid != '${Const.GLOBAL_WEBAPP_UUID}'")
    fun getAllWebApps(): List<WebAppEntity>

    @Query("SELECT * FROM webapps WHERE uuid = '${Const.GLOBAL_WEBAPP_UUID}' LIMIT 1")
    fun getGlobalSettings(): WebAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertAll(entities: List<WebAppEntity>)

    @Upsert fun upsert(entity: WebAppEntity)

    @Upsert fun upsertAll(entities: List<WebAppEntity>)

    @Query("DELETE FROM webapps WHERE uuid = :uuid") fun deleteByUuid(uuid: String)

    @Query("DELETE FROM webapps WHERE uuid != '${Const.GLOBAL_WEBAPP_UUID}'") fun deleteAllWebApps()

    @Transaction
    fun replaceAllWebApps(entities: List<WebAppEntity>) {
        deleteAllWebApps()
        insertAll(entities)
    }
}
