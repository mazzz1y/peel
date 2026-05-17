package wtf.mazy.peel.model

import kotlinx.serialization.Serializable

@Serializable
data class ProxySurrogate(
    val uuid: String,
    val name: String = "",
    val type: Int = Proxy.TYPE_HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val password: String? = null,
    val remoteDns: Boolean = false,
    val bypassList: List<String> = emptyList(),
)
