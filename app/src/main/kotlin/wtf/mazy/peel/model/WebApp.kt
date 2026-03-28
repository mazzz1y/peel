package wtf.mazy.peel.model

import android.app.Activity
import android.content.Context
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import java.io.File
import java.util.Objects
import java.util.UUID

internal fun deleteAppPrefs(context: Context, uuid: String) {
    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    prefsDir.listFiles { f -> f.name.startsWith(uuid) }?.forEach { it.delete() }
}

data class WebApp(var baseUrl: String, override val uuid: String = UUID.randomUUID().toString()) :
    IconOwner, SandboxOwner {
    override var title: String
    override val letterIconSeed: String
        get() = baseUrl

    override var isUseContainer = false
    override var isEphemeralSandbox = false
    var order = 0
    var groupUuid: String? = null

    var settings = WebAppSettings()

    init {
        title =
            baseUrl
                .takeIf { it.isNotEmpty() }
                ?.replace("http://", "")
                ?.replace("https://", "")
                ?.replace("www.", "") ?: baseUrl
    }

    constructor(baseUrl: String, uuid: String, order: Int) : this(baseUrl, uuid) {
        this.order = order
    }

    constructor(other: WebApp) : this(other.baseUrl, other.uuid) {
        title = other.title
        isUseContainer = other.isUseContainer
        isEphemeralSandbox = other.isEphemeralSandbox
        order = other.order
        groupUuid = other.groupUuid
        settings = other.settings.deepCopy()
    }

    fun deleteShortcuts(activity: Activity) {
        ShortcutIconUtils.deleteShortcuts(listOf(uuid), activity)
    }

    fun cleanupWebAppData(activity: Activity) {
        if (isUseContainer) SandboxManager.clearSandboxData(activity, uuid)
        deleteIcon()
        deleteAppPrefs(activity, uuid)
    }

    val contentFingerprint: Int
        get() = Objects.hash(title, baseUrl, groupUuid, isUseContainer, isEphemeralSandbox, order, hasCustomIcon)

    fun resolveContextId(): String? {
        val group = groupUuid?.let { DataManager.instance.getGroup(it) }
        return when {
            isUseContainer -> uuid
            group?.isUseContainer == true -> group.uuid
            else -> null
        }
    }

    fun resolvePrivateMode(): Boolean =
        isEphemeralSandbox ||
                (groupUuid?.let { DataManager.instance.getGroup(it) }?.isEphemeralSandbox == true)
}
