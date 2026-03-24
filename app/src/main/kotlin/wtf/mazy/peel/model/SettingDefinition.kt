package wtf.mazy.peel.model

import androidx.annotation.StringRes
import wtf.mazy.peel.R
import kotlin.reflect.KMutableProperty1

data class SettingField(
    val property: KMutableProperty1<WebAppSettings, *>,
    val defaultValue: Any?,
) {
    val key: String
        get() = property.name
}

sealed class SettingDefinition(
    val toggle: SettingField,
    @param:StringRes val displayNameResId: Int,
    val category: SettingCategory,
    val globalOnly: Boolean = false,
) {
    val key: String
        get() = toggle.key

    open val allFields: List<SettingField>
        get() = listOf(toggle)

    class BooleanSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        globalOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, category, globalOnly)

    class TriStateSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val valueOff: Int = 0,
        val valueMid: Int = 1,
        val valueOn: Int = 2,
        @StringRes val labelOff: Int = R.string.permission_deny,
        @StringRes val labelMid: Int = R.string.permission_ask,
        @StringRes val labelOn: Int = R.string.permission_allow,
    ) : SettingDefinition(toggle, displayNameResId, category)

    class BooleanWithIntSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val intField: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, category) {
        override val allFields
            get() = listOf(toggle, intField)
    }

    class StringMapSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
    ) : SettingDefinition(toggle, displayNameResId, category)

    class BooleanWithCredentialsSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val usernameField: SettingField,
        val passwordField: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, category) {
        override val allFields
            get() = listOf(toggle, usernameField, passwordField)
    }
}

enum class SettingCategory(@param:StringRes val displayNameResId: Int) {
    GENERAL(R.string.general),
    APPEARANCE(R.string.appearance),
    PERMISSIONS(R.string.permissions),
    CONTENT(R.string.content),
    SECURITY(R.string.security),
    ADVANCED(R.string.advanced),
}

object SettingRegistry {

    private val ALL_SETTINGS: List<SettingDefinition> =
        listOf(
            // General
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowJs, true),
                R.string.allow_javascript,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isRequestDesktop, false),
                R.string.request_website_in_desktop_version,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isOpenUrlExternal, true),
                R.string.open_external_links_in_browser_app,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isLongClickShare, true),
                R.string.setting_long_click_share,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isPullToRefresh, false),
                R.string.setting_pull_to_refresh,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowNotification, true),
                R.string.setting_floating_controls,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isKeepAwake, false),
                R.string.keep_screen_awake,
                SettingCategory.GENERAL,
            ),
            SettingDefinition.BooleanWithIntSetting(
                SettingField(WebAppSettings::isAutoReload, false),
                R.string.webapp_autoreload,
                SettingCategory.GENERAL,
                intField = SettingField(WebAppSettings::timeAutoReload, 60),
            ),
            // Appearance
            SettingDefinition.TriStateSetting(
                SettingField(WebAppSettings::colorScheme, WebAppSettings.COLOR_SCHEME_AUTO),
                R.string.setting_color_scheme,
                SettingCategory.APPEARANCE,
                labelOff = R.string.color_scheme_auto,
                labelMid = R.string.color_scheme_light,
                labelOn = R.string.color_scheme_dark,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDynamicStatusBar, true),
                R.string.setting_dynamic_status_bar,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowProgressbar, true),
                R.string.show_progress_bar_during_page_load,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowFullscreen, false),
                R.string.show_fullscreen,
                SettingCategory.APPEARANCE,
            ),
            // Permissions
            SettingDefinition.TriStateSetting(
                SettingField(WebAppSettings::isCameraPermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_camera_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.TriStateSetting(
                SettingField(WebAppSettings::isMicrophonePermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_microphone_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.TriStateSetting(
                SettingField(WebAppSettings::isAllowLocationAccess, WebAppSettings.PERMISSION_ASK),
                R.string.allow_location_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.TriStateSetting(
                SettingField(WebAppSettings::isAppLinksPermission, WebAppSettings.PERMISSION_ASK),
                R.string.open_app_links,
                SettingCategory.PERMISSIONS,
            ),
            // Content
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDrmAllowed, false),
                R.string.allow_drm_content,
                SettingCategory.CONTENT,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowMediaPlaybackInBackground, false),
                R.string.allow_media_playback_in_background,
                SettingCategory.CONTENT,
            ),
            // Security
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAlwaysHttps, true),
                R.string.setting_always_https,
                SettingCategory.SECURITY,
            ),

            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isSafeBrowsing, true),
                R.string.setting_enhanced_tracking_protection,
                SettingCategory.SECURITY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isGlobalPrivacyControl, false),
                R.string.setting_global_privacy_control,
                SettingCategory.SECURITY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isFingerprintingProtection, false),
                R.string.setting_fingerprinting_protection,
                SettingCategory.SECURITY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockLocalNetwork, false),
                R.string.setting_block_local_network,
                SettingCategory.SECURITY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBiometricProtection, false),
                R.string.enable_access_restriction,
                SettingCategory.SECURITY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableScreenshots, false),
                R.string.setting_disable_screenshots,
                SettingCategory.SECURITY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isClearCache, false),
                R.string.clear_cache_after_usage,
                SettingCategory.SECURITY,
            ),
            // Advanced
            SettingDefinition.StringMapSetting(
                SettingField(WebAppSettings::customHeaders, null),
                R.string.setting_custom_headers,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanWithCredentialsSetting(
                SettingField(WebAppSettings::isUseBasicAuth, false),
                R.string.setting_basic_auth,
                SettingCategory.ADVANCED,
                usernameField = SettingField(WebAppSettings::basicAuthUsername, ""),
                passwordField = SettingField(WebAppSettings::basicAuthPassword, ""),
            ),
        )

    fun getAllSettings(): List<SettingDefinition> = ALL_SETTINGS

    fun getPerAppSettings(): List<SettingDefinition> = ALL_SETTINGS.filter { !it.globalOnly }

    fun getSettingByKey(key: String): SettingDefinition? {
        return ALL_SETTINGS.find { it.key == key }
    }
}
