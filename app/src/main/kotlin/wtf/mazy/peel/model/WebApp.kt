package wtf.mazy.peel.model

import android.app.Activity
import java.util.UUID
import wtf.mazy.peel.shortcut.ShortcutIconUtils

data class WebApp(var baseUrl: String, override val uuid: String = UUID.randomUUID().toString()) :
    IconOwner {
    override var title: String
    override val letterIconSeed: String get() = baseUrl
    var isActiveEntry = true
    var isUseContainer = false
    var isEphemeralSandbox = false
    var order = 0
    var groupUuid: String? = null

    var settings = WebAppSettings()

    val effectiveSettings: WebAppSettings
        get() {
            val globalSettings = DataManager.instance.defaultSettings.settings
            val groupSettings = groupUuid?.let { DataManager.instance.getGroup(it)?.settings }
            return if (groupSettings != null) {
                settings.getEffective(groupSettings, globalSettings)
            } else {
                settings.getEffective(globalSettings)
            }
        }

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
        settings = other.settings.copy()
    }

    fun markInactive(activity: Activity) {
        isActiveEntry = false
        ShortcutIconUtils.deleteShortcuts(listOf(uuid), activity)
    }

    fun cleanupWebAppData(activity: Activity) {
        SandboxManager.clearSandboxData(activity, uuid)
        deleteIcon()
    }
}
