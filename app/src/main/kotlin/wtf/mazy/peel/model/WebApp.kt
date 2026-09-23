package wtf.mazy.peel.model

import android.content.Context
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.prettyHostLabel
import java.io.File
import java.util.Objects
import java.util.UUID

internal fun deleteAppPrefs(context: Context, uuid: String) {
    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    prefsDir.listFiles { f -> f.name.startsWith(uuid) }?.forEach { it.delete() }
}

class WebApp(var baseUrl: String, override val uuid: String = UUID.randomUUID().toString()) :
    IconOwner, SandboxOwner {
    override var title: String
    override val letterIconSeed: String
        get() = baseUrl

    override var isUseContainer = false
    override var isEphemeralSandbox = false
    override var proxyUuid: String? = null
    var isPrivateSession = false
    var order = 0
    var groupUuid: String? = null

    var settings = WebAppSettings()

    init {
        title = baseUrl.takeIf { it.isNotEmpty() }?.let { prettyHostLabel(it) } ?: baseUrl
    }

    constructor(other: WebApp) : this(other.baseUrl, other.uuid) {
        title = other.title
        isUseContainer = other.isUseContainer
        isEphemeralSandbox = other.isEphemeralSandbox
        isPrivateSession = other.isPrivateSession
        proxyUuid = other.proxyUuid
        order = other.order
        groupUuid = other.groupUuid
        settings = other.settings.deepCopy()
    }

    fun cloneWith(groupUuid: String?, order: Int): WebApp {
        val clone = WebApp(baseUrl)
        clone.title = title
        clone.isUseContainer = isUseContainer
        clone.isEphemeralSandbox = isEphemeralSandbox
        clone.isPrivateSession = isPrivateSession
        clone.proxyUuid = proxyUuid
        clone.groupUuid = groupUuid
        clone.order = order
        clone.settings = settings.deepCopy()
        return clone
    }

    fun deleteShortcuts(context: Context) {
        ShortcutIconUtils.deleteShortcuts(listOf(uuid), context)
    }

    suspend fun cleanupWebAppData(context: Context) {
        if (isUseContainer) SandboxManager.clearSandboxData(context, uuid)
        deleteIcon()
        deleteAppPrefs(context, uuid)
    }

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
        val group = groupUuid?.let { DataManager.instance.getGroup(it) }
        return when {
            isUseContainer -> uuid
            group?.isUseContainer == true -> group.uuid
            else -> null
        }
    }

    fun resolvePrivateMode(): Boolean = isPrivateSession

    override fun resolveEphemeral(): Boolean =
        isEphemeralSandbox ||
                (groupUuid?.let { DataManager.instance.getGroup(it) }?.isEphemeralSandbox == true)

    fun resolveEphemeralContextId(): String? =
        uuid.takeIf { !isPrivateSession && isUseContainer && resolveEphemeral() }

    fun resolveProxyUuid(): String? {
        if (!isUseContainer) {
            val group = groupUuid?.let { DataManager.instance.getGroup(it) }
            if (group?.isUseContainer == true) return group.proxyUuid
            return null
        }
        if (proxyUuid != null) return proxyUuid
        val group = groupUuid?.let { DataManager.instance.getGroup(it) }
        return group?.proxyUuid
    }
}
