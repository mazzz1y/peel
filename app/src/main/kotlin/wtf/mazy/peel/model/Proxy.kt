package wtf.mazy.peel.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Proxy(
    val uuid: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: Int = TYPE_HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val password: String? = null,
    val remoteDns: Boolean = false,
    val bypassList: List<String> = emptyList(),
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
