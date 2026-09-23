package wtf.mazy.peel.model

interface SandboxOwner<Self : SandboxOwner<Self>> {
    val uuid: String
    val isUseContainer: Boolean
    val isEphemeralSandbox: Boolean
    val proxyUuid: String?

    fun resolveEphemeral(): Boolean = isEphemeralSandbox

    fun withSandbox(
        isUseContainer: Boolean = this.isUseContainer,
        isEphemeralSandbox: Boolean = this.isEphemeralSandbox,
        proxyUuid: String? = this.proxyUuid,
    ): Self
}
