package wtf.mazy.peel.model

import java.util.Objects
import java.util.UUID

data class WebAppGroup(
    override val uuid: String = UUID.randomUUID().toString(),
    override val title: String = "",
    val order: Int = 0,
    override val isolation: Isolation<ProxyRoute> = Isolation.Shared,
    val settings: WebAppSettings = WebAppSettings(),
) : IconOwner, SandboxOwner<WebAppGroup, ProxyRoute> {

    override val defaultProxy: ProxyRoute
        get() = ProxyRoute.Direct

    override fun withIsolation(isolation: Isolation<ProxyRoute>): WebAppGroup =
        copy(isolation = isolation)

    val contentFingerprint: Int
        get() = Objects.hash(title, isolation)
}
