package wtf.mazy.peel.model.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PushSubscriptionDao {

    @Query("SELECT * FROM push_subscriptions")
    fun getAll(): List<PushSubscriptionEntity>

    @Query("SELECT * FROM push_subscriptions WHERE instance = :instance LIMIT 1")
    fun getByInstance(instance: String): PushSubscriptionEntity?

    @Query("SELECT * FROM push_subscriptions WHERE scope = :scope LIMIT 1")
    fun getByScope(scope: String): PushSubscriptionEntity?

    @Query("SELECT * FROM push_subscriptions WHERE contextId = :contextId")
    fun getByContextId(contextId: String): List<PushSubscriptionEntity>

    @Upsert
    fun upsert(entity: PushSubscriptionEntity)

    @Query("DELETE FROM push_subscriptions WHERE instance = :instance")
    fun deleteByInstance(instance: String)
}
