package wtf.mazy.peel.model

import kotlinx.serialization.Serializable
import kotlin.reflect.KMutableProperty1

@Serializable
data class WebAppSettings(
    var isOpenUrlExternal: Boolean? = null,
    var isAllowJs: Boolean? = null,
    var isRequestDesktop: Boolean? = null,
    var isClearCache: Boolean? = null,
    var isAlwaysHttps: Boolean? = null,
    var isAllowLocationAccess: Int? = null,
    var isAutoReload: Boolean? = null,
    var timeAutoReload: Int? = null,
    var colorScheme: Int? = null,
    var webContentZoom: Int? = null,
    var isDrmAllowed: Boolean? = null,
    var isShowFullscreen: Boolean? = null,
    var isKeepAwake: Boolean? = null,
    var isCameraPermission: Int? = null,
    var isMicrophonePermission: Int? = null,
    var isBiometricProtection: Boolean? = null,
    var isAllowMediaPlaybackInBackground: Boolean? = null,
    var isLongClickShare: Boolean? = null,
    var isShowProgressbar: Boolean? = null,
    var isDisableScreenshots: Boolean? = null,
    var isPullToRefresh: Boolean? = null,
    var isSafeBrowsing: Int? = null,
    var isDynamicStatusBar: Boolean? = null,
    var browserControlsMode: Int? = null,
    var isAppLinksPermission: Int? = null,
    var isGlobalPrivacyControl: Boolean? = null,
    var isFingerprintingProtection: Boolean? = null,
    var isBlockLocalNetwork: Boolean? = null,
    var isBlockWebRtcIpLeak: Boolean? = null,
    var isDisableQuic: Boolean? = null,
    var isDisableEch: Boolean? = null,
    var isUseSystemCerts: Boolean? = null,
    var isUseBasicAuth: Boolean? = null,
    var basicAuthUsername: String? = null,
    var basicAuthPassword: String? = null,
    var isUseCustomUserAgent: Boolean? = null,
    var customUserAgent: String? = null,
    var isUseCustomLocale: Boolean? = null,
    var customLocale: String? = null,
    var customGeckoPrefs: Map<String, String>? = null,
    var isAllowCertBypass: Boolean? = null,
    var isTranslatorEnabled: Boolean? = null,
    var autoTranslatePairs: Map<String, String>? = null,
    var sameAppDomains: List<String>? = null,
    var blockedDomains: List<String>? = null,
    var skipHistoryDomains: List<String>? = null,
    var trustedCertificates: List<String>? = null,
) {
    companion object {
        const val PERMISSION_OFF = 0
        const val PERMISSION_ASK = 1
        const val PERMISSION_ON = 2

        const val COLOR_SCHEME_AUTO = 0
        const val COLOR_SCHEME_LIGHT = 1
        const val COLOR_SCHEME_DARK = 2

        const val WEB_CONTENT_ZOOM_DEFAULT = 100
        val WEB_CONTENT_ZOOM_VALUES = intArrayOf(50, 75, 100, 125, 150, 175, 200)

        const val TRACKER_PROTECTION_NONE = 0
        const val TRACKER_PROTECTION_DEFAULT = 1
        const val TRACKER_PROTECTION_STRICT = 2

        const val BROWSER_CONTROLS_OFF = 0
        const val BROWSER_CONTROLS_BUTTON = 1
        const val BROWSER_CONTROLS_BAR = 2
        const val BROWSER_CONTROLS_PANEL = 3

        @Suppress("UNCHECKED_CAST")
        private val PROPERTY_MAP: Map<String, KMutableProperty1<WebAppSettings, Any?>> by lazy {
            val map = mutableMapOf<String, KMutableProperty1<WebAppSettings, Any?>>()
            for (setting in SettingRegistry.all) {
                for (field in setting.allFields) {
                    map[field.key] = field.property as KMutableProperty1<WebAppSettings, Any?>
                }
            }
            map
        }

        val DEFAULTS: Map<String, Any?> by lazy {
            val map = mutableMapOf<String, Any?>()
            for (setting in SettingRegistry.all) {
                for (field in setting.allFields) {
                    map[field.key] = field.defaultValue
                }
            }
            map
        }

        val ALL_KEYS: Set<String>
            get() = PROPERTY_MAP.keys

        fun createWithDefaults(): WebAppSettings {
            val settings = WebAppSettings()
            DEFAULTS.forEach { (key, value) -> settings.setValue(key, value) }
            return settings
        }
    }

    fun upgradeUrl(url: String): String =
        if (isAlwaysHttps == true && url.startsWith("http://")) {
            url.replaceFirst("http://", "https://")
        } else url

    fun getValue(key: String): Any? {
        return PROPERTY_MAP[key]?.get(this)
    }

    fun setValue(key: String, value: Any?) {
        PROPERTY_MAP[key]?.set(this, value)
    }

    fun ensureAllConcrete() {
        DEFAULTS.forEach { (key, defaultValue) ->
            if (getValue(key) == null) {
                setValue(key, defaultValue)
            }
        }
    }

    fun getEffective(vararg parents: WebAppSettings): WebAppSettings {
        val effective = WebAppSettings()
        ALL_KEYS.forEach { key ->
            val value =
                this.getValue(key)
                    ?: parents.firstNotNullOfOrNull { it.getValue(key) }
                    ?: DEFAULTS[key]
            effective.setValue(key, value)
        }
        return effective
    }

    val overriddenKeys: List<String>
        get() = ALL_KEYS.filter { getValue(it) != null }

    fun sanitize(asOverride: Boolean = false) {
        SettingRegistry.all.forEach { it.sanitize(this, asOverride) }
    }

    fun deepCopy() = copy(
        customGeckoPrefs = customGeckoPrefs?.toMap(),
        autoTranslatePairs = autoTranslatePairs?.toMap(),
        sameAppDomains = sameAppDomains?.toList(),
        blockedDomains = blockedDomains?.toList(),
        skipHistoryDomains = skipHistoryDomains?.toList(),
        trustedCertificates = trustedCertificates?.toList(),
    )
}
