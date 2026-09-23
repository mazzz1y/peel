package wtf.mazy.peel.model

import kotlinx.serialization.Serializable
import kotlin.reflect.KMutableProperty1

/** Settings after override → group → global resolution; every field is concrete. */
@Serializable
data class EffectiveSettings(
    val openUrlExternal: Boolean,
    val allowJs: Boolean,
    val requestDesktop: Boolean,
    val clearCache: Boolean,
    val alwaysHttps: Boolean,
    val locationPermission: Int,
    val autoReload: Boolean,
    val autoReloadInterval: Int,
    val colorScheme: Int,
    val webContentZoom: Int,
    val drmAllowed: Boolean,
    val showFullscreen: Boolean,
    val keepAwake: Boolean,
    val cameraPermission: Int,
    val microphonePermission: Int,
    val biometricProtection: Boolean,
    val backgroundMediaPlayback: Boolean,
    val longClickShare: Boolean,
    val showProgressBar: Boolean,
    val disableScreenshots: Boolean,
    val pullToRefresh: Boolean,
    val trackerProtection: Int,
    val dynamicStatusBar: Boolean,
    val browserControlsMode: Int,
    val appLinksPermission: Int,
    val globalPrivacyControl: Boolean,
    val fingerprintingProtection: Boolean,
    val blockLocalNetwork: Boolean,
    val blockWebRtcIpLeak: Boolean,
    val disableQuic: Boolean,
    val disableEch: Boolean,
    val useSystemCerts: Boolean,
    val useBasicAuth: Boolean,
    val basicAuthUsername: String,
    val basicAuthPassword: String,
    val useCustomUserAgent: Boolean,
    val customUserAgent: String,
    val useCustomLocale: Boolean,
    val customLocale: String,
    val customGeckoPrefs: Map<String, String>,
    val allowCertBypass: Boolean,
    val translatorEnabled: Boolean,
    val autoTranslatePairs: Map<String, String>,
    val sameAppDomains: List<String>,
    val blockedDomains: List<String>,
    val skipHistoryDomains: List<String>,
    val trustedCertificates: List<String>,
) {
    fun upgradeUrl(url: String): String =
        if (alwaysHttps && url.startsWith("http://")) {
            url.replaceFirst("http://", "https://")
        } else url
}

fun WebAppSettings.effective(vararg parents: WebAppSettings): EffectiveSettings {
    val resolved = getEffective(*parents)
    return EffectiveSettings(
        openUrlExternal = resolved.value(WebAppSettings::isOpenUrlExternal),
        allowJs = resolved.value(WebAppSettings::isAllowJs),
        requestDesktop = resolved.value(WebAppSettings::isRequestDesktop),
        clearCache = resolved.value(WebAppSettings::isClearCache),
        alwaysHttps = resolved.value(WebAppSettings::isAlwaysHttps),
        locationPermission = resolved.value(WebAppSettings::isAllowLocationAccess),
        autoReload = resolved.value(WebAppSettings::isAutoReload),
        autoReloadInterval = resolved.value(WebAppSettings::timeAutoReload),
        colorScheme = resolved.value(WebAppSettings::colorScheme),
        webContentZoom = resolved.value(WebAppSettings::webContentZoom),
        drmAllowed = resolved.value(WebAppSettings::isDrmAllowed),
        showFullscreen = resolved.value(WebAppSettings::isShowFullscreen),
        keepAwake = resolved.value(WebAppSettings::isKeepAwake),
        cameraPermission = resolved.value(WebAppSettings::isCameraPermission),
        microphonePermission = resolved.value(WebAppSettings::isMicrophonePermission),
        biometricProtection = resolved.value(WebAppSettings::isBiometricProtection),
        backgroundMediaPlayback = resolved.value(WebAppSettings::isAllowMediaPlaybackInBackground),
        longClickShare = resolved.value(WebAppSettings::isLongClickShare),
        showProgressBar = resolved.value(WebAppSettings::isShowProgressbar),
        disableScreenshots = resolved.value(WebAppSettings::isDisableScreenshots),
        pullToRefresh = resolved.value(WebAppSettings::isPullToRefresh),
        trackerProtection = resolved.value(WebAppSettings::isSafeBrowsing),
        dynamicStatusBar = resolved.value(WebAppSettings::isDynamicStatusBar),
        browserControlsMode = resolved.value(WebAppSettings::browserControlsMode),
        appLinksPermission = resolved.value(WebAppSettings::isAppLinksPermission),
        globalPrivacyControl = resolved.value(WebAppSettings::isGlobalPrivacyControl),
        fingerprintingProtection = resolved.value(WebAppSettings::isFingerprintingProtection),
        blockLocalNetwork = resolved.value(WebAppSettings::isBlockLocalNetwork),
        blockWebRtcIpLeak = resolved.value(WebAppSettings::isBlockWebRtcIpLeak),
        disableQuic = resolved.value(WebAppSettings::isDisableQuic),
        disableEch = resolved.value(WebAppSettings::isDisableEch),
        useSystemCerts = resolved.value(WebAppSettings::isUseSystemCerts),
        useBasicAuth = resolved.value(WebAppSettings::isUseBasicAuth),
        basicAuthUsername = resolved.value(WebAppSettings::basicAuthUsername),
        basicAuthPassword = resolved.value(WebAppSettings::basicAuthPassword),
        useCustomUserAgent = resolved.value(WebAppSettings::isUseCustomUserAgent),
        customUserAgent = resolved.value(WebAppSettings::customUserAgent),
        useCustomLocale = resolved.value(WebAppSettings::isUseCustomLocale),
        customLocale = resolved.value(WebAppSettings::customLocale),
        customGeckoPrefs = resolved.customGeckoPrefs.orEmpty(),
        allowCertBypass = resolved.value(WebAppSettings::isAllowCertBypass),
        translatorEnabled = resolved.value(WebAppSettings::isTranslatorEnabled),
        autoTranslatePairs = resolved.autoTranslatePairs.orEmpty(),
        sameAppDomains = resolved.sameAppDomains.orEmpty(),
        blockedDomains = resolved.blockedDomains.orEmpty(),
        skipHistoryDomains = resolved.skipHistoryDomains.orEmpty(),
        trustedCertificates = resolved.trustedCertificates.orEmpty(),
    )
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> WebAppSettings.value(property: KMutableProperty1<WebAppSettings, T?>): T =
    property.get(this) ?: WebAppSettings.DEFAULTS.getValue(property.name) as T
