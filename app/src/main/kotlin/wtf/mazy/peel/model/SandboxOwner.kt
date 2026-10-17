package wtf.mazy.peel.model

interface SandboxOwner<Self : SandboxOwner<Self, P>, P : ProxyChoice> {
    val uuid: String
    val isolation: Isolation<P>
    val defaultProxy: P

    fun withIsolation(isolation: Isolation<P>): Self
}

fun <S : SandboxOwner<S, P>, P : ProxyChoice> S.sandboxed(): S =
    withIsolation(Isolation.Sandboxed(ephemeral = false, proxy = defaultProxy))

fun <S : SandboxOwner<S, P>, P : ProxyChoice> S.withEphemeral(ephemeral: Boolean): S {
    val current = isolation as? Isolation.Sandboxed ?: return this
    return withIsolation(current.copy(ephemeral = ephemeral))
}

fun <S : SandboxOwner<S, P>, P : ProxyChoice> S.withProxy(proxy: P): S {
    val current = isolation as? Isolation.Sandboxed ?: return this
    return withIsolation(current.copy(proxy = proxy))
}

fun <S : SandboxOwner<S, P>, P : ProxyChoice> S.withExistingProxy(proxyUuids: Set<String>): S {
    val current = isolation as? Isolation.Sandboxed ?: return this
    val via = current.proxy as? ProxyRoute.Via ?: return this
    if (via.proxyUuid in proxyUuids) return this
    return withIsolation(current.copy(proxy = defaultProxy))
}
