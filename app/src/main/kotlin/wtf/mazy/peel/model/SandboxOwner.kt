package wtf.mazy.peel.model

interface SandboxOwner {
    val uuid: String
    var isUseContainer: Boolean
    var isEphemeralSandbox: Boolean
    var proxyUuid: String?

    fun resolveEphemeral(): Boolean = isEphemeralSandbox
}
