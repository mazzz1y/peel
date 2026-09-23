package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.util.prettyHostLabel
import java.io.File
import java.util.Objects
import java.util.UUID

internal fun deleteAppPrefs(context: Context, uuid: String) {
    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    prefsDir.listFiles { f -> f.name.startsWith(uuid) }?.forEach { it.delete() }
}

data class WebApp(
    val baseUrl: String,
    override val uuid: String = UUID.randomUUID().toString(),
    override val title: String = defaultTitle(baseUrl),
    override val isUseContainer: Boolean = false,
    override val isEphemeralSandbox: Boolean = false,
    override val proxyUuid: String? = null,
    val isPrivateSession: Boolean = false,
    val order: Int = 0,
    val groupUuid: String? = null,
    val settings: WebAppSettings = WebAppSettings(),
) : IconOwner, SandboxOwner<WebApp> {

    override val letterIconSeed: String
        get() = baseUrl

    override fun withSandbox(
        isUseContainer: Boolean,
        isEphemeralSandbox: Boolean,
        proxyUuid: String?,
    ): WebApp = copy(
        isUseContainer = isUseContainer,
        isEphemeralSandbox = isEphemeralSandbox,
        proxyUuid = proxyUuid,
    )

    val contentFingerprint: Int
        get() = Objects.hash(
            title,
            baseUrl,
            groupUuid,
            isUseContainer,
            isEphemeralSandbox,
            proxyUuid,
            iconStamp,
        )

    fun resolveContextId(): String? {
        if (isPrivateSession) return null
        val group = groupUuid?.let { DataManager.group(it) }
        return when {
            isUseContainer -> uuid
            group?.isUseContainer == true -> group.uuid
            else -> null
        }
    }

    fun resolvePrivateMode(): Boolean = isPrivateSession

    override fun resolveEphemeral(): Boolean =
        isEphemeralSandbox ||
                (groupUuid?.let { DataManager.group(it) }?.isEphemeralSandbox == true)

    fun resolveEphemeralContextId(): String? =
        uuid.takeIf { !isPrivateSession && isUseContainer && resolveEphemeral() }

    fun resolveProxyUuid(): String? {
        if (!isUseContainer) {
            val group = groupUuid?.let { DataManager.group(it) }
            if (group?.isUseContainer == true) return group.proxyUuid
            return null
        }
        if (proxyUuid != null) return proxyUuid
        val group = groupUuid?.let { DataManager.group(it) }
        return group?.proxyUuid
    }

    companion object {
        fun defaultTitle(baseUrl: String): String =
            baseUrl.takeIf { it.isNotEmpty() }?.let { prettyHostLabel(it) } ?: baseUrl
    }
}
