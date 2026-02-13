package wtf.mazy.peel.model

import android.app.Activity
import java.io.File
import java.util.UUID
import wtf.mazy.peel.shortcut.ShortcutIconUtils
import wtf.mazy.peel.util.App
import wtf.mazy.peel.util.Const

data class WebApp(var baseUrl: String, val uuid: String = UUID.randomUUID().toString()) {
    var title: String
    var isActiveEntry = true
    var isUseContainer = false
    var isEphemeralSandbox = false
    var order = 0

    var settings = WebAppSettings()

    val iconFile: File
        get() = File(App.appContext.filesDir, "icons/${uuid}.png")

    val hasCustomIcon: Boolean
        get() = iconFile.exists()

    val effectiveSettings: WebAppSettings
        get() = settings.getEffective(DataManager.instance.defaultSettings.settings)

    init {
        title =
            baseUrl
                .takeIf { it.isNotEmpty() }
                ?.replace("http://", "")
                ?.replace("https://", "")
                ?.replace("www.", "") ?: baseUrl
        initDefaultSettings()
    }

    constructor(baseUrl: String, uuid: String, order: Int) : this(baseUrl, uuid) {
        this.order = order
    }

    constructor(other: WebApp) : this(other.baseUrl, other.uuid) {
        title = other.title
        isUseContainer = other.isUseContainer
        isEphemeralSandbox = other.isEphemeralSandbox
        order = other.order
        settings = other.settings.copy()
    }

    private fun initDefaultSettings() {
        if (baseUrl.contains("facebook.com")) {
            settings.customHeaders = mutableMapOf("User-Agent" to Const.DESKTOP_USER_AGENT)
        }
    }

    fun markInactive(activity: Activity) {
        isActiveEntry = false
        ShortcutIconUtils.deleteShortcuts(listOf(uuid), activity)
    }

    fun deleteIcon() {
        try {
            if (iconFile.exists()) {
                iconFile.delete()
            }
        } catch (_: Exception) {}
    }

    fun cleanupWebAppData(activity: Activity) {
        try {
            val webViewDir = File(activity.applicationContext.filesDir.parent, "app_webview_$uuid")
            if (webViewDir.exists()) {
                webViewDir.deleteRecursively()
            }
        } catch (_: Exception) {}

        deleteIcon()
    }
}
