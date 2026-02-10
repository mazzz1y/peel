package wtf.mazy.peel.model

import wtf.mazy.peel.R

data class SettingDefinition(
    val key: String,
    val displayName: String,
    val category: SettingCategory,
    val type: SettingType,
    val secondaryKey: String? = null,
    val tertiaryKey: String? = null,
)

enum class SettingCategory(val displayNameResId: Int) {
    GENERAL(R.string.general),
    PRIVACY(R.string.webapp_section_security),
    PERMISSIONS(R.string.permissions),
    ADVANCED(R.string.advanced),
    APPEARANCE(R.string.appearance),
}

enum class SettingType {
    BOOLEAN,
    BOOLEAN_WITH_INT,
    TIME_RANGE,
    STRING_MAP,
}

/** Registry of all available settings that can be overridden */
object SettingRegistry {

    fun getAllSettings(): List<SettingDefinition> {
        return listOf(
            // General
            SettingDefinition(
                "isAllowJs",
                "Allow JavaScript",
                SettingCategory.GENERAL,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isRequestDesktop",
                "Request Desktop Site",
                SettingCategory.GENERAL,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isOpenUrlExternal",
                "Open External Links in Browser",
                SettingCategory.GENERAL,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isLongClickShare",
                "Long-click to Share Links",
                SettingCategory.GENERAL,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isAllowCookies",
                "Allow Cookies",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isAllowThirdPartyCookies",
                "Allow Third-Party Cookies",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isClearCache",
                "Clear Cache on Exit",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isBlockImages",
                "Block Images",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isBlockThirdPartyRequests",
                "Block Third-Party Requests",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),

            // Permissions
            SettingDefinition(
                "isAllowLocationAccess",
                "Allow Location Access",
                SettingCategory.PERMISSIONS,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isCameraPermission",
                "Allow Camera",
                SettingCategory.PERMISSIONS,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isMicrophonePermission",
                "Allow Microphone",
                SettingCategory.PERMISSIONS,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isDrmAllowed",
                "Allow DRM Content",
                SettingCategory.PERMISSIONS,
                SettingType.BOOLEAN,
            ),

            // Appearance
            SettingDefinition(
                "isShowProgressbar",
                "Show Progress Bar",
                SettingCategory.APPEARANCE,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isShowFullscreen",
                "Fullscreen Mode",
                SettingCategory.APPEARANCE,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isForceDarkMode",
                "Force Dark Mode",
                SettingCategory.APPEARANCE,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isUseTimespanDarkMode",
                "Dark Mode Schedule",
                SettingCategory.APPEARANCE,
                SettingType.TIME_RANGE,
                "timespanDarkModeBegin",
                "timespanDarkModeEnd",
            ),
            SettingDefinition(
                "isEnableZooming",
                "Enable Zooming",
                SettingCategory.APPEARANCE,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isPullToRefresh",
                "Pull to Refresh",
                SettingCategory.GENERAL,
                SettingType.BOOLEAN,
            ),

            // Advanced
            SettingDefinition(
                "isAlwaysHttps",
                "Always HTTPS",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isIgnoreSslErrors",
                "Ignore SSL Errors",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isBiometricProtection",
                "Biometric Protection",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isKeepAwake",
                "Keep Screen Awake",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isAutoReload",
                "Auto-Reload",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN_WITH_INT,
                "timeAutoReload",
            ),
            SettingDefinition(
                "isAllowMediaPlaybackInBackground",
                "Background Media Playback",
                SettingCategory.ADVANCED,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "isDisableScreenshots",
                "Disable Screenshots",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
            SettingDefinition(
                "customHeaders",
                "Custom Headers",
                SettingCategory.ADVANCED,
                SettingType.STRING_MAP,
            ),
            SettingDefinition(
                "isSafeBrowsing",
                "Safe Browsing",
                SettingCategory.PRIVACY,
                SettingType.BOOLEAN,
            ),
        )
    }

    fun getSettingByKey(key: String): SettingDefinition? {
        return getAllSettings().find { it.key == key }
    }
}
