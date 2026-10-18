package wtf.mazy.peel.model

sealed interface Isolation<out P : ProxyChoice> {
    val isSandboxed: Boolean
        get() = this is Sandboxed

    val isEphemeral: Boolean
        get() = this is Sandboxed && ephemeral

    val proxyOrNull: P?
        get() = (this as? Sandboxed)?.proxy

    data object Shared : Isolation<Nothing>

    data class Sandboxed<out P : ProxyChoice>(val ephemeral: Boolean, val proxy: P) : Isolation<P>
}
