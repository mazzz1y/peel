package wtf.mazy.peel.model

import androidx.annotation.StringRes
import kotlin.reflect.KMutableProperty1
import wtf.mazy.peel.R

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
) {
    val key: String
        get() = toggle.key

    open val allFields: List<SettingField>
        get() = listOf(toggle)

    class BooleanSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
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

    class TimeRangeSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val start: SettingField,
        val end: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, category) {
        override val allFields
            get() = listOf(toggle, start, end)
    }

    class StringMapSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
    ) : SettingDefinition(toggle, displayNameResId, category)
}

enum class SettingCategory(@param:StringRes val displayNameResId: Int) {
    GENERAL(R.string.general),
    PRIVACY(R.string.webapp_section_security),
    PERMISSIONS(R.string.permissions),
    ADVANCED(R.string.advanced),
    APPEARANCE(R.string.appearance),
}

object SettingRegistry {

    private val ALL_SETTINGS: List<SettingDefinition> =
        listOf(
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
                SettingField(WebAppSettings::isOpenUrlExternal, false),
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
                SettingField(WebAppSettings::isAllowCookies, true),
                R.string.accept_cookies,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowThirdPartyCookies, false),
                R.string.accept_third_party_cookies,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isClearCache, false),
                R.string.clear_cache_after_usage,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockImages, false),
                R.string.do_not_load_images,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockThirdPartyRequests, false),
                R.string.block_all_third_party_requests,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableScreenshots, false),
                R.string.setting_disable_screenshots,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isSafeBrowsing, true),
                R.string.setting_safe_browsing,
                SettingCategory.PRIVACY,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowLocationAccess, false),
                R.string.allow_location_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isCameraPermission, false),
                R.string.allow_camera_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isMicrophonePermission, false),
                R.string.allow_microphone_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDrmAllowed, false),
                R.string.allow_drm_content,
                SettingCategory.PERMISSIONS,
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
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isForceDarkMode, false),
                R.string.force_dark_mode,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.TimeRangeSetting(
                SettingField(WebAppSettings::isUseTimespanDarkMode, false),
                R.string.limit_dark_mode_to_time_span,
                SettingCategory.APPEARANCE,
                start = SettingField(WebAppSettings::timespanDarkModeBegin, "22:00"),
                end = SettingField(WebAppSettings::timespanDarkModeEnd, "06:00"),
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isEnableZooming, false),
                R.string.activate_two_finger_zoom,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAlwaysHttps, true),
                R.string.setting_always_https,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isIgnoreSslErrors, false),
                R.string.ignore_ssl_errors,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBiometricProtection, false),
                R.string.enable_access_restriction,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isKeepAwake, false),
                R.string.keep_screen_awake,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanWithIntSetting(
                SettingField(WebAppSettings::isAutoReload, false),
                R.string.webapp_autoreload,
                SettingCategory.ADVANCED,
                intField = SettingField(WebAppSettings::timeAutoReload, 0),
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowMediaPlaybackInBackground, false),
                R.string.allow_media_playback_in_background,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.StringMapSetting(
                SettingField(WebAppSettings::customHeaders, null),
                R.string.setting_custom_headers,
                SettingCategory.ADVANCED,
            ),
        )

    fun getAllSettings(): List<SettingDefinition> = ALL_SETTINGS

    fun getSettingByKey(key: String): SettingDefinition? {
        return ALL_SETTINGS.find { it.key == key }
    }
}
