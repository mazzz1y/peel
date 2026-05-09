package wtf.mazy.peel.model

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import wtf.mazy.peel.R
import wtf.mazy.peel.activities.BrowserActivity
import kotlin.reflect.KMutableProperty1

data class SettingField(
    val property: KMutableProperty1<WebAppSettings, *>,
    val defaultValue: Any?,
) {
    val key: String
        get() = property.name
}

sealed class SettingDefinition(
    val primaryField: SettingField,
    @param:StringRes val displayNameResId: Int,
    val category: SettingCategory,
    val globalOnly: Boolean = false,
) {
    val key: String
        get() = primaryField.key

    open val allFields: List<SettingField>
        get() = listOf(primaryField)

    class BooleanSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        globalOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, category, globalOnly)

    class ChoiceSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        globalOnly: Boolean = false,
        val values: IntArray,
        @param:StringRes val labels: IntArray,
    ) : SettingDefinition(toggle, displayNameResId, category, globalOnly) {
        init {
            require(values.size == labels.size) { "values and labels count must match" }
            require(values.isNotEmpty()) { "at least one choice required" }
        }

        companion object {
            fun permissionChoice(
                toggle: SettingField,
                @StringRes displayNameResId: Int,
                category: SettingCategory,
                globalOnly: Boolean = false,
            ) = ChoiceSetting(
                toggle, displayNameResId, category, globalOnly,
                values = intArrayOf(
                    WebAppSettings.PERMISSION_OFF,
                    WebAppSettings.PERMISSION_ASK,
                    WebAppSettings.PERMISSION_ON,
                ),
                labels = intArrayOf(
                    R.string.permission_deny,
                    R.string.permission_ask,
                    R.string.permission_allow,
                ),
            )
        }
    }

    class BooleanWithIntSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val intField: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, category) {
        override val allFields
            get() = listOf(primaryField, intField)
    }

    class BooleanWithCredentialsSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val usernameField: SettingField,
        val passwordField: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, category) {
        override val allFields
            get() = listOf(primaryField, usernameField, passwordField)
    }

    class BooleanWithStringSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        val stringField: SettingField,
        @param:StringRes val hintResId: Int,
        globalOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, category, globalOnly) {
        override val allFields
            get() = listOf(primaryField, stringField)
    }

    class StringMapSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        category: SettingCategory,
        @param:StringRes val keyHintResId: Int,
        @param:StringRes val valueHintResId: Int,
        globalOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, category, globalOnly)
}

enum class SettingCategory(@param:StringRes val displayNameResId: Int) {
    APPEARANCE(R.string.appearance),
    BEHAVIOR(R.string.behavior),
    PERMISSIONS(R.string.permissions),
    CONTENT(R.string.content),
    NETWORK_PRIVACY(R.string.network_privacy),
    PROTECTION(R.string.protection),
    ADVANCED(R.string.advanced),
}

enum class ApplyTiming {
    IMMEDIATE,
    WEBAPP_RESTART,
    PEEL_RESTART,
}

object ApplyTimingRegistry {
    private val WEBAPP_RESTART_KEYS = setOf(
        "isAllowJs",
        "isRequestDesktop",
        "isLongClickShare",
        "isDynamicStatusBar",
        "isAllowMediaPlaybackInBackground",
        "isUseCustomUserAgent",
        "customUserAgent",
    )

    private val PEEL_RESTART_KEYS = setOf(
        "isSafeBrowsing",
        "isGlobalPrivacyControl",
        "isFingerprintingProtection",
        "isBlockLocalNetwork",
        "isBlockWebRtcIpLeak",
        "isDisableQuic",
        "isUseCustomLocale",
        "customLocale",
        "customGeckoPrefs",
        "colorScheme",
    )

    fun getTiming(key: String): ApplyTiming = when (key) {
        in PEEL_RESTART_KEYS -> ApplyTiming.PEEL_RESTART
        in WEBAPP_RESTART_KEYS -> ApplyTiming.WEBAPP_RESTART
        else -> ApplyTiming.IMMEDIATE
    }

    fun getChangedKeys(original: WebAppSettings, modified: WebAppSettings): Set<String> {
        return WebAppSettings.ALL_KEYS.filterTo(mutableSetOf()) { key ->
            original.getValue(key) != modified.getValue(key)
        }
    }

    fun getHighestTiming(keys: Set<String>): ApplyTiming {
        var highest = ApplyTiming.IMMEDIATE
        for (key in keys) {
            val t = getTiming(key)
            if (t > highest) highest = t
        }
        return highest
    }

    const val EXTRA_APPLY_TIMING = "apply_timing"

    fun showSnackbarForTiming(
        timing: ApplyTiming,
        root: View,
        restartAction: (() -> Unit)? = null,
    ): Snackbar? {
        if (timing == ApplyTiming.IMMEDIATE) return null
        if (timing == ApplyTiming.WEBAPP_RESTART && !BrowserActivity.hasLiveInstances()) return null
        val message = when (timing) {
            ApplyTiming.PEEL_RESTART -> R.string.setting_requires_peel_restart
            ApplyTiming.WEBAPP_RESTART -> R.string.setting_requires_webapp_restart
            ApplyTiming.IMMEDIATE -> return null
        }
        return Snackbar.make(root, message, Snackbar.LENGTH_LONG).apply {
            if (restartAction != null) setAction(R.string.restart) { restartAction() }
            show()
        }
    }
}

object SettingRegistry {

    private val ALL_SETTINGS: List<SettingDefinition> =
        listOf(
            // Appearance
            SettingDefinition.ChoiceSetting(
                SettingField(WebAppSettings::colorScheme, WebAppSettings.COLOR_SCHEME_AUTO),
                R.string.setting_color_scheme,
                SettingCategory.APPEARANCE,
                globalOnly = true,
                values = intArrayOf(
                    WebAppSettings.COLOR_SCHEME_AUTO,
                    WebAppSettings.COLOR_SCHEME_LIGHT,
                    WebAppSettings.COLOR_SCHEME_DARK,
                ),
                labels = intArrayOf(
                    R.string.color_scheme_auto,
                    R.string.color_scheme_light,
                    R.string.color_scheme_dark,
                ),
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
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isPullToRefresh, true),
                R.string.setting_pull_to_refresh,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowNotification, true),
                R.string.setting_floating_controls,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isLongClickShare, true),
                R.string.setting_long_click_share,
                SettingCategory.APPEARANCE,
            ),
            // Behavior
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isRequestDesktop, false),
                R.string.request_website_in_desktop_version,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isOpenUrlExternal, true),
                R.string.open_external_links_in_browser_app,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isKeepAwake, false),
                R.string.keep_screen_awake,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanWithIntSetting(
                SettingField(WebAppSettings::isAutoReload, false),
                R.string.webapp_autoreload,
                SettingCategory.BEHAVIOR,
                intField = SettingField(WebAppSettings::timeAutoReload, 60),
            ),
            // Permissions
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isCameraPermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_camera_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isMicrophonePermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_microphone_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isAllowLocationAccess, WebAppSettings.PERMISSION_ASK),
                R.string.allow_location_access,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
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
            // Network & Privacy
            SettingDefinition.ChoiceSetting(
                SettingField(
                    WebAppSettings::isSafeBrowsing,
                    WebAppSettings.TRACKER_PROTECTION_DEFAULT
                ),
                R.string.setting_tracker_protection,
                SettingCategory.NETWORK_PRIVACY,
                globalOnly = true,
                values = intArrayOf(
                    WebAppSettings.TRACKER_PROTECTION_NONE,
                    WebAppSettings.TRACKER_PROTECTION_DEFAULT,
                    WebAppSettings.TRACKER_PROTECTION_STRICT,
                ),
                labels = intArrayOf(
                    R.string.tracker_protection_none,
                    R.string.tracker_protection_default,
                    R.string.tracker_protection_strict,
                ),
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isGlobalPrivacyControl, true),
                R.string.setting_global_privacy_control,
                SettingCategory.NETWORK_PRIVACY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isFingerprintingProtection, true),
                R.string.setting_fingerprinting_protection,
                SettingCategory.NETWORK_PRIVACY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockLocalNetwork, true),
                R.string.setting_block_local_network,
                SettingCategory.NETWORK_PRIVACY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockWebRtcIpLeak, true),
                R.string.setting_block_webrtc_ip_leak,
                SettingCategory.NETWORK_PRIVACY,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAlwaysHttps, true),
                R.string.setting_always_https,
                SettingCategory.NETWORK_PRIVACY,
            ),
            // Protection
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBiometricProtection, false),
                R.string.enable_access_restriction,
                SettingCategory.PROTECTION,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableScreenshots, false),
                R.string.setting_disable_screenshots,
                SettingCategory.PROTECTION,
            ),
            // Advanced
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowJs, true),
                R.string.allow_javascript,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanWithCredentialsSetting(
                SettingField(WebAppSettings::isUseBasicAuth, false),
                R.string.setting_basic_auth,
                SettingCategory.ADVANCED,
                usernameField = SettingField(WebAppSettings::basicAuthUsername, ""),
                passwordField = SettingField(WebAppSettings::basicAuthPassword, ""),
            ),
            SettingDefinition.BooleanWithStringSetting(
                SettingField(WebAppSettings::isUseCustomUserAgent, false),
                R.string.custom_user_agent,
                SettingCategory.ADVANCED,
                stringField = SettingField(WebAppSettings::customUserAgent, ""),
                hintResId = R.string.user_agent_hint,
            ),
            SettingDefinition.BooleanWithStringSetting(
                SettingField(WebAppSettings::isUseCustomLocale, false),
                R.string.custom_locale,
                SettingCategory.ADVANCED,
                stringField = SettingField(WebAppSettings::customLocale, ""),
                hintResId = R.string.custom_locale_hint,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableQuic, false),
                R.string.setting_disable_quic,
                SettingCategory.ADVANCED,
                globalOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isClearCache, false),
                R.string.clear_cache_after_usage,
                SettingCategory.ADVANCED,
                globalOnly = true,
            ),
            SettingDefinition.StringMapSetting(
                SettingField(WebAppSettings::customGeckoPrefs, null),
                R.string.setting_custom_gecko_prefs,
                SettingCategory.ADVANCED,
                keyHintResId = R.string.setting_custom_gecko_prefs_key_hint,
                valueHintResId = R.string.setting_custom_gecko_prefs_value_hint,
                globalOnly = true,
            ),
        )

    fun getAllSettings(): List<SettingDefinition> = ALL_SETTINGS

    fun getPerAppSettings(): List<SettingDefinition> = ALL_SETTINGS.filter { !it.globalOnly }

    fun getSettingByKey(key: String): SettingDefinition? {
        return ALL_SETTINGS.find { it.key == key }
    }
}
