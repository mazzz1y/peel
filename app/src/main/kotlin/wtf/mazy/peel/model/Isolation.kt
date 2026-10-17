package wtf.mazy.peel.model

sealed interface Isolation<out P : ProxyChoice> {
    data object Shared : Isolation<Nothing>

    data class Sandboxed<out P : ProxyChoice>(val ephemeral: Boolean, val proxy: P) : Isolation<P>
}

val Isolation<*>.isSandboxed: Boolean
    get() = this is Isolation.Sandboxed

val Isolation<*>.isEphemeral: Boolean
    get() = this is Isolation.Sandboxed && ephemeral

val <P : ProxyChoice> Isolation<P>.proxyOrNull: P?
    get() = (this as? Isolation.Sandboxed)?.proxy
