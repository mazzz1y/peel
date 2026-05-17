package wtf.mazy.peel.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Proxy(
    val uuid: String = UUID.randomUUID().toString(),
    var name: String = "",
    var type: Int = TYPE_HTTP,
    var host: String = "",
    var port: Int = 0,
    var username: String? = null,
    var password: String? = null,
    var remoteDns: Boolean = false,
    var bypassList: List<String> = emptyList(),
) {
    fun summary(): String {
        val label = when (type) {
            TYPE_HTTP -> "HTTP"
            TYPE_HTTPS -> "HTTPS"
            TYPE_SOCKS4 -> "SOCKS4"
            TYPE_SOCKS5 -> "SOCKS5"
            else -> "?"
        }
        return "$label $host:$port"
    }

    fun displayName(): String = name.ifBlank { summary() }

    val contentFingerprint: String
        get() = listOf(
            name, type, host, port,
            username.orEmpty(), password.orEmpty(),
            remoteDns, bypassList.joinToString(","),
        ).joinToString("|")

    companion object {
        const val TYPE_HTTP = 0
        const val TYPE_HTTPS = 1
        const val TYPE_SOCKS4 = 2
        const val TYPE_SOCKS5 = 3
    }
}
