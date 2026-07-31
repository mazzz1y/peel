package wtf.mazy.peel.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "push_subscriptions")
data class PushSubscriptionEntity(
    @PrimaryKey val instance: String,
    val contextId: String?,
    val scope: String,
    val endpoint: String,
    val appServerKey: String?,
)
