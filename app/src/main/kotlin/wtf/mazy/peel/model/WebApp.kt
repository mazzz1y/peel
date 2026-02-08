package wtf.mazy.peel.model

import android.app.Activity
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.ShortcutIconUtils
import java.util.UUID

data class WebApp(var baseUrl: String, val uuid: String = UUID.randomUUID().toString()) {
    var title: String
    var isActiveEntry = true
    var isUseContainer = false
    var order = 0
    var customIconPath: String? = null
    var hasCustomIcon = false

    var settings = WebAppSettings()

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
        customIconPath = other.customIconPath
        hasCustomIcon = other.hasCustomIcon
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

    fun cleanupWebAppData(activity: Activity) {
        try {
            val webViewDir =
                java.io.File(activity.applicationContext.filesDir.parent, "app_webview_$uuid")
            if (webViewDir.exists()) {
                webViewDir.deleteRecursively()
                android.util.Log.d("WebApp", "Deleted WebView data directory for webapp: $uuid")
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "WebApp", "Failed to delete WebView data directory for webapp $uuid", e)
        }

        if (hasCustomIcon && customIconPath != null) {
            try {
                val iconFile = java.io.File(customIconPath!!)
                if (iconFile.exists()) {
                    iconFile.delete()
                    android.util.Log.d("WebApp", "Deleted custom icon for webapp: $uuid")
                }
            } catch (e: Exception) {
                android.util.Log.w("WebApp", "Failed to delete custom icon for webapp $uuid", e)
            }
        }
    }
}
