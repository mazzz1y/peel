package wtf.mazy.peel.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxies")
data class ProxyEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val type: Int,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val remoteDns: Boolean,
    val bypassList: String,
)
