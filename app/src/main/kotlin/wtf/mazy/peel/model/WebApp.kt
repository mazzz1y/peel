package wtf.mazy.peel.model

import android.app.Activity
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import java.util.Objects
import java.util.UUID

data class WebApp(var baseUrl: String, override val uuid: String = UUID.randomUUID().toString()) :
    IconOwner, SandboxOwner {
    override var title: String
    override val letterIconSeed: String
        get() = baseUrl

    var isActiveEntry = true
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
        isActiveEntry = other.isActiveEntry
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
        SandboxManager.clearSandboxData(activity, uuid)
        deleteIcon()
    }

    val contentFingerprint: Int
        get() = Objects.hash(title, baseUrl, groupUuid, isUseContainer, isEphemeralSandbox, order)
}
