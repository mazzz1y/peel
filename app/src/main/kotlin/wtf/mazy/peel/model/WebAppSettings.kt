package wtf.mazy.peel.model

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

data class WebAppSettings(
    var isOpenUrlExternal: Boolean? = null,
    var isAllowCookies: Boolean? = null,
    var isAllowThirdPartyCookies: Boolean? = null,
    var isAllowJs: Boolean? = null,
    var isRequestDesktop: Boolean? = null,
    var isClearCache: Boolean? = null,
    var isBlockImages: Boolean? = null,
    var isAlwaysHttps: Boolean? = null,
    var isAllowLocationAccess: Boolean? = null,
    var customHeaders: MutableMap<String, String>? = null,
    var isAutoReload: Boolean? = null,
    var timeAutoReload: Int? = null,
    var isForceDarkMode: Boolean? = null,
    var isUseTimespanDarkMode: Boolean? = null,
    var timespanDarkModeBegin: String? = null,
    var timespanDarkModeEnd: String? = null,
    var isIgnoreSslErrors: Boolean? = null,
    var isBlockThirdPartyRequests: Boolean? = null,
    var isDrmAllowed: Boolean? = null,
    var isShowFullscreen: Boolean? = null,
    var isKeepAwake: Boolean? = null,
    var isCameraPermission: Boolean? = null,
    var isMicrophonePermission: Boolean? = null,
    var isEnableZooming: Boolean? = null,
    var isBiometricProtection: Boolean? = null,
    var isAllowMediaPlaybackInBackground: Boolean? = null,
    var isLongClickShare: Boolean? = null,
    var isShowProgressbar: Boolean? = null,
    var isDisableScreenshots: Boolean? = null,
    var isPullToRefresh: Boolean? = null,
) {
    companion object {
        val DEFAULTS =
            mapOf(
                "isOpenUrlExternal" to false,
                "isAllowCookies" to true,
                "isAllowThirdPartyCookies" to false,
                "isAllowJs" to true,
                "isRequestDesktop" to false,
                "isClearCache" to false,
                "isBlockImages" to false,
                "isAlwaysHttps" to true,
                "isAllowLocationAccess" to false,
                "customHeaders" to null,
                "isAutoReload" to false,
                "timeAutoReload" to 0,
                "isForceDarkMode" to false,
                "isUseTimespanDarkMode" to false,
                "timespanDarkModeBegin" to "22:00",
                "timespanDarkModeEnd" to "06:00",
                "isIgnoreSslErrors" to false,
                "isBlockThirdPartyRequests" to false,
                "isDrmAllowed" to false,
                "isShowFullscreen" to false,
                "isKeepAwake" to false,
                "isCameraPermission" to false,
                "isMicrophonePermission" to false,
                "isEnableZooming" to false,
                "isBiometricProtection" to false,
                "isAllowMediaPlaybackInBackground" to false,
                "isLongClickShare" to true,
                "isShowProgressbar" to true,
                "isDisableScreenshots" to false,
                "isPullToRefresh" to false,
            )

        fun createWithDefaults(): WebAppSettings {
            val settings = WebAppSettings()
            DEFAULTS.forEach { (key, value) -> settings.setValue(key, value) }
            return settings
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getValue(key: String): Any? {
        return try {
            val property =
                WebAppSettings::class.memberProperties.find { it.name == key }
                    as? KMutableProperty1<WebAppSettings, Any?>
            property?.get(this)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun setValue(key: String, value: Any?) {
        try {
            val property =
                WebAppSettings::class.memberProperties.find { it.name == key }
                    as? KMutableProperty1<WebAppSettings, Any?>
            property?.set(this, value)
        } catch (_: Exception) {}
    }

    fun ensureAllConcrete() {
        DEFAULTS.forEach { (key, defaultValue) ->
            if (getValue(key) == null) {
                setValue(key, defaultValue)
            }
        }
    }

    fun getEffective(globalSettings: WebAppSettings): WebAppSettings {
        val effective = WebAppSettings()
        DEFAULTS.keys.forEach { key ->
            val value = this.getValue(key) ?: globalSettings.getValue(key) ?: DEFAULTS[key]
            effective.setValue(key, value)
        }
        return effective
    }

    fun getOverriddenKeys(): List<String> {
        return DEFAULTS.keys.filter { getValue(it) != null }
    }
}
