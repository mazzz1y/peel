package wtf.mazy.peel.model

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import wtf.mazy.peel.R
import kotlin.reflect.KMutableProperty1

data class SettingField(
    val property: KMutableProperty1<WebAppSettings, *>,
    val defaultValue: Any?,
    val timing: ApplyTiming = ApplyTiming.IMMEDIATE,
) {
    val key: String
        get() = property.name
}

sealed class SettingDefinition(
    val primaryField: SettingField,
    @param:StringRes val displayNameResId: Int,
    @param:StringRes val descriptionResId: Int,
    val category: SettingCategory,
    val engineOnly: Boolean = false,
) {
    val key: String
        get() = primaryField.key

    @get:LayoutRes
    abstract val layoutRes: Int

    open val allFields: List<SettingField>
        get() = listOf(primaryField)

    open fun sanitize(settings: WebAppSettings, asOverride: Boolean) = Unit

    protected fun WebAppSettings.isEnabled(): Boolean = getValue(key) as? Boolean ?: false

    protected fun WebAppSettings.clear(vararg fields: SettingField) =
        fields.forEach { setValue(it.key, null) }

    class BooleanSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        engineOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_boolean
    }

    class ChoiceSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        engineOnly: Boolean = false,
        val values: IntArray,
        @param:StringRes val labels: IntArray,
        @param:StringRes val shortLabels: IntArray = labels,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_dropdown

        init {
            require(values.size == labels.size) { "values and labels count must match" }
            require(shortLabels.size == labels.size) { "short labels count must match" }
            require(values.isNotEmpty()) { "at least one choice required" }
        }

        companion object {
            fun permissionChoice(
                toggle: SettingField,
                @StringRes displayNameResId: Int,
                @StringRes descriptionResId: Int,
                category: SettingCategory,
                engineOnly: Boolean = false,
            ) = ChoiceSetting(
                toggle, displayNameResId, descriptionResId, category, engineOnly,
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
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        val intField: SettingField,
        @param:StringRes val intLabelResId: Int,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category) {
        override val layoutRes get() = R.layout.item_setting_boolean_int

        override val allFields
            get() = listOf(primaryField, intField)

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            if (!settings.isEnabled()) return
            val value = settings.getValue(intField.key) as? Int
            if (value != null && value > 0) return
            if (asOverride) {
                settings.clear(primaryField, intField)
            } else {
                settings.setValue(key, false)
                settings.setValue(intField.key, intField.defaultValue)
            }
        }
    }

    class BooleanWithCredentialsSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        val usernameField: SettingField,
        val passwordField: SettingField,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category) {
        override val layoutRes get() = R.layout.item_setting_boolean_value

        override val allFields
            get() = listOf(primaryField, usernameField, passwordField)

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            if (!settings.isEnabled()) return
            val username = (settings.getValue(usernameField.key) as? String).orEmpty()
            val password = (settings.getValue(passwordField.key) as? String).orEmpty()
            if (username.isNotEmpty() || password.isNotEmpty()) return
            if (asOverride) {
                settings.clear(primaryField, usernameField, passwordField)
            } else {
                settings.setValue(key, false)
            }
        }
    }

    class BooleanWithStringSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        val stringField: SettingField,
        @param:StringRes val hintResId: Int,
        engineOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_boolean_value

        override val allFields
            get() = listOf(primaryField, stringField)

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            if (!settings.isEnabled()) return
            if ((settings.getValue(stringField.key) as? String).orEmpty().isNotEmpty()) return
            if (asOverride) {
                settings.clear(primaryField, stringField)
            } else {
                settings.setValue(key, false)
            }
        }
    }

    class StringMapSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        @param:StringRes val keyHintResId: Int,
        @param:StringRes val valueHintResId: Int,
        engineOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_string_collection

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            @Suppress("UNCHECKED_CAST")
            val map = settings.getValue(key) as? Map<String, String>
            val cleaned = map
                ?.mapKeys { it.key.trim() }
                ?.mapValues { it.value.trim() }
                ?.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            settings.setValue(
                key,
                when {
                    cleaned.isNullOrEmpty() -> if (asOverride) null else emptyMap()
                    else -> cleaned
                },
            )
        }
    }

    class StringListSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        engineOnly: Boolean = false,
        val entryKind: EntryKind = EntryKind.DOMAIN,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_string_collection

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            @Suppress("UNCHECKED_CAST")
            val list = settings.getValue(key) as? List<String>
            val cleaned = list
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
            settings.setValue(
                key,
                when {
                    cleaned.isNullOrEmpty() -> if (asOverride) null else emptyList()
                    else -> cleaned
                },
            )
        }

        enum class EntryKind { DOMAIN, CERTIFICATE }
    }

    class LanguagePairMapSetting(
        toggle: SettingField,
        @StringRes displayNameResId: Int,
        @StringRes descriptionResId: Int,
        category: SettingCategory,
        val mapField: SettingField,
        engineOnly: Boolean = false,
    ) : SettingDefinition(toggle, displayNameResId, descriptionResId, category, engineOnly) {
        override val layoutRes get() = R.layout.item_setting_language_pair_map

        override val allFields
            get() = listOf(primaryField, mapField)

        override fun sanitize(settings: WebAppSettings, asOverride: Boolean) {
            if (!settings.isEnabled()) {
                if (asOverride) settings.clear(primaryField, mapField) else settings.clear(mapField)
                return
            }
            @Suppress("UNCHECKED_CAST")
            val map = settings.getValue(mapField.key) as? Map<String, String>
            val cleaned = map
                ?.filter { it.key.isNotBlank() && it.value.isNotBlank() && it.key != it.value }
            settings.setValue(mapField.key, cleaned?.takeIf { it.isNotEmpty() })
        }
    }
}

enum class SettingSection(@param:StringRes val displayNameResId: Int) {
    GLOBAL(R.string.global_settings),
    ENGINE(R.string.settings_section_engine),
}

enum class SettingCategory(@param:StringRes val displayNameResId: Int) {
    APPEARANCE(R.string.appearance),
    BEHAVIOR(R.string.behavior),
    NAVIGATION(R.string.navigation),
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
    fun timingOf(key: String): ApplyTiming =
        SettingRegistry.fieldByKey(key)?.timing ?: ApplyTiming.IMMEDIATE

    fun changedKeys(original: WebAppSettings, modified: WebAppSettings): Set<String> =
        WebAppSettings.ALL_KEYS.filterTo(mutableSetOf()) { key ->
            original.getValue(key) != modified.getValue(key)
        }

    fun highestTiming(keys: Set<String>): ApplyTiming =
        keys.maxOfOrNull(::timingOf) ?: ApplyTiming.IMMEDIATE

    const val EXTRA_APPLY_TIMING = "apply_timing"
}

object SettingRegistry {

    private val ALL_SETTINGS: List<SettingDefinition> =
        listOf(
            // Appearance
            SettingDefinition.ChoiceSetting(
                SettingField(
                    WebAppSettings::colorScheme,
                    WebAppSettings.COLOR_SCHEME_AUTO,
                    ApplyTiming.PEEL_RESTART
                ),
                R.string.setting_color_scheme,
                R.string.setting_color_scheme_desc,
                SettingCategory.APPEARANCE,
                engineOnly = true,
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
            SettingDefinition.ChoiceSetting(
                SettingField(
                    WebAppSettings::webContentZoom,
                    WebAppSettings.WEB_CONTENT_ZOOM_DEFAULT,
                    ApplyTiming.PEEL_RESTART
                ),
                R.string.setting_web_content_scale,
                R.string.setting_web_content_scale_desc,
                SettingCategory.APPEARANCE,
                engineOnly = true,
                values = WebAppSettings.WEB_CONTENT_ZOOM_VALUES,
                labels = intArrayOf(
                    R.string.web_content_scale_50,
                    R.string.web_content_scale_75,
                    R.string.web_content_scale_100,
                    R.string.web_content_scale_125,
                    R.string.web_content_scale_150,
                    R.string.web_content_scale_175,
                    R.string.web_content_scale_200,
                ),
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDynamicStatusBar, true, ApplyTiming.WEBAPP_RESTART),
                R.string.setting_dynamic_status_bar,
                R.string.setting_dynamic_status_bar_desc,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowProgressbar, true),
                R.string.show_progress_bar_during_page_load,
                R.string.show_progress_bar_during_page_load_desc,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isShowFullscreen, false),
                R.string.show_fullscreen,
                R.string.show_fullscreen_desc,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isPullToRefresh, true),
                R.string.setting_pull_to_refresh,
                R.string.setting_pull_to_refresh_desc,
                SettingCategory.APPEARANCE,
            ),
            SettingDefinition.ChoiceSetting(
                SettingField(
                    WebAppSettings::browserControlsMode,
                    WebAppSettings.BROWSER_CONTROLS_BUTTON,
                ),
                R.string.setting_browser_controls,
                R.string.setting_browser_controls_desc,
                SettingCategory.APPEARANCE,
                values = intArrayOf(
                    WebAppSettings.BROWSER_CONTROLS_OFF,
                    WebAppSettings.BROWSER_CONTROLS_BUTTON,
                    WebAppSettings.BROWSER_CONTROLS_BAR,
                    WebAppSettings.BROWSER_CONTROLS_PANEL,
                ),
                labels = intArrayOf(
                    R.string.browser_controls_off,
                    R.string.browser_controls_button,
                    R.string.browser_controls_bar,
                    R.string.browser_controls_panel,
                ),
                shortLabels = intArrayOf(
                    R.string.browser_controls_off,
                    R.string.browser_controls_button_short,
                    R.string.browser_controls_bar_short,
                    R.string.browser_controls_panel_short,
                ),
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isLongClickShare, true, ApplyTiming.WEBAPP_RESTART),
                R.string.setting_long_click_share,
                R.string.setting_long_click_share_desc,
                SettingCategory.APPEARANCE,
            ),
            // Behavior
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isRequestDesktop, false, ApplyTiming.WEBAPP_RESTART),
                R.string.request_website_in_desktop_version,
                R.string.request_website_in_desktop_version_desc,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isOpenUrlExternal, true),
                R.string.setting_external_link_prompt,
                R.string.setting_external_link_prompt_desc,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isKeepAwake, false),
                R.string.keep_screen_awake,
                R.string.keep_screen_awake_desc,
                SettingCategory.BEHAVIOR,
            ),
            SettingDefinition.BooleanWithIntSetting(
                SettingField(WebAppSettings::isAutoReload, false),
                R.string.webapp_autoreload,
                R.string.webapp_autoreload_desc,
                SettingCategory.BEHAVIOR,
                intField = SettingField(WebAppSettings::timeAutoReload, 60),
                intLabelResId = R.string.setting_interval_seconds,
            ),
            SettingDefinition.LanguagePairMapSetting(
                SettingField(WebAppSettings::isTranslatorEnabled, false),
                R.string.setting_translator,
                R.string.setting_translator_desc,
                SettingCategory.BEHAVIOR,
                mapField = SettingField(WebAppSettings::autoTranslatePairs, null),
            ),
            // Permissions
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isCameraPermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_camera_access,
                R.string.allow_camera_access_desc,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isMicrophonePermission, WebAppSettings.PERMISSION_ASK),
                R.string.allow_microphone_access,
                R.string.allow_microphone_access_desc,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isAllowLocationAccess, WebAppSettings.PERMISSION_ASK),
                R.string.allow_location_access,
                R.string.allow_location_access_desc,
                SettingCategory.PERMISSIONS,
            ),
            SettingDefinition.ChoiceSetting.permissionChoice(
                SettingField(WebAppSettings::isAppLinksPermission, WebAppSettings.PERMISSION_ASK),
                R.string.open_app_links,
                R.string.open_app_links_desc,
                SettingCategory.PERMISSIONS,
            ),
            // Content
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDrmAllowed, false),
                R.string.allow_drm_content,
                R.string.allow_drm_content_desc,
                SettingCategory.CONTENT,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(
                    WebAppSettings::isAllowMediaPlaybackInBackground,
                    false,
                    ApplyTiming.WEBAPP_RESTART
                ),
                R.string.allow_media_playback_in_background,
                R.string.allow_media_playback_in_background_desc,
                SettingCategory.CONTENT,
            ),
            // Network & Privacy
            SettingDefinition.ChoiceSetting(
                SettingField(
                    WebAppSettings::isSafeBrowsing,
                    WebAppSettings.TRACKER_PROTECTION_DEFAULT,
                    ApplyTiming.PEEL_RESTART,
                ),
                R.string.setting_tracker_protection,
                R.string.setting_tracker_protection_desc,
                SettingCategory.NETWORK_PRIVACY,
                engineOnly = true,
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
                SettingField(
                    WebAppSettings::isGlobalPrivacyControl,
                    true,
                    ApplyTiming.PEEL_RESTART
                ),
                R.string.setting_global_privacy_control,
                R.string.setting_global_privacy_control_desc,
                SettingCategory.NETWORK_PRIVACY,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(
                    WebAppSettings::isFingerprintingProtection,
                    true,
                    ApplyTiming.PEEL_RESTART
                ),
                R.string.setting_fingerprinting_protection,
                R.string.setting_fingerprinting_protection_desc,
                SettingCategory.NETWORK_PRIVACY,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockLocalNetwork, true, ApplyTiming.PEEL_RESTART),
                R.string.setting_block_local_network,
                R.string.setting_block_local_network_desc,
                SettingCategory.NETWORK_PRIVACY,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBlockWebRtcIpLeak, true, ApplyTiming.PEEL_RESTART),
                R.string.setting_block_webrtc_ip_leak,
                R.string.setting_block_webrtc_ip_leak_desc,
                SettingCategory.NETWORK_PRIVACY,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAlwaysHttps, true),
                R.string.setting_always_https,
                R.string.setting_always_https_desc,
                SettingCategory.NETWORK_PRIVACY,
            ),
            // Protection
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isBiometricProtection, false),
                R.string.enable_access_restriction,
                R.string.enable_access_restriction_desc,
                SettingCategory.PROTECTION,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableScreenshots, false),
                R.string.setting_disable_screenshots,
                R.string.setting_disable_screenshots_desc,
                SettingCategory.PROTECTION,
            ),
            // Advanced
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowJs, true, ApplyTiming.WEBAPP_RESTART),
                R.string.allow_javascript,
                R.string.allow_javascript_desc,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isAllowCertBypass, false),
                R.string.setting_allow_cert_bypass,
                R.string.setting_allow_cert_bypass_desc,
                SettingCategory.ADVANCED,
            ),
            SettingDefinition.BooleanWithCredentialsSetting(
                SettingField(WebAppSettings::isUseBasicAuth, false),
                R.string.setting_basic_auth,
                R.string.setting_basic_auth_desc,
                SettingCategory.ADVANCED,
                usernameField = SettingField(WebAppSettings::basicAuthUsername, ""),
                passwordField = SettingField(WebAppSettings::basicAuthPassword, ""),
            ),
            SettingDefinition.BooleanWithStringSetting(
                SettingField(
                    WebAppSettings::isUseCustomUserAgent,
                    false,
                    ApplyTiming.WEBAPP_RESTART
                ),
                R.string.custom_user_agent,
                R.string.custom_user_agent_desc,
                SettingCategory.ADVANCED,
                stringField = SettingField(
                    WebAppSettings::customUserAgent,
                    "",
                    ApplyTiming.WEBAPP_RESTART
                ),
                hintResId = R.string.user_agent_hint,
            ),
            SettingDefinition.BooleanWithStringSetting(
                SettingField(WebAppSettings::isUseCustomLocale, false, ApplyTiming.PEEL_RESTART),
                R.string.custom_locale,
                R.string.custom_locale_desc,
                SettingCategory.ADVANCED,
                stringField = SettingField(
                    WebAppSettings::customLocale,
                    "",
                    ApplyTiming.PEEL_RESTART
                ),
                hintResId = R.string.custom_locale_hint,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableQuic, false, ApplyTiming.PEEL_RESTART),
                R.string.setting_disable_quic,
                R.string.setting_disable_quic_desc,
                SettingCategory.ADVANCED,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isDisableEch, false, ApplyTiming.PEEL_RESTART),
                R.string.setting_disable_ech,
                R.string.setting_disable_ech_desc,
                SettingCategory.ADVANCED,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isClearCache, false),
                R.string.clear_cache_after_usage,
                R.string.clear_cache_after_usage_desc,
                SettingCategory.ADVANCED,
                engineOnly = true,
            ),
            SettingDefinition.BooleanSetting(
                SettingField(WebAppSettings::isUseSystemCerts, false, ApplyTiming.PEEL_RESTART),
                R.string.setting_use_system_certs,
                R.string.setting_use_system_certs_desc,
                SettingCategory.ADVANCED,
                engineOnly = true,
            ),
            SettingDefinition.StringListSetting(
                SettingField(WebAppSettings::trustedCertificates, null),
                R.string.setting_trusted_certificates,
                R.string.setting_trusted_certificates_desc,
                SettingCategory.ADVANCED,
                engineOnly = true,
                entryKind = SettingDefinition.StringListSetting.EntryKind.CERTIFICATE,
            ),
            SettingDefinition.StringMapSetting(
                SettingField(WebAppSettings::customGeckoPrefs, null, ApplyTiming.PEEL_RESTART),
                R.string.setting_custom_gecko_prefs,
                R.string.setting_custom_gecko_prefs_desc,
                SettingCategory.ADVANCED,
                keyHintResId = R.string.setting_custom_gecko_prefs_key_hint,
                valueHintResId = R.string.setting_custom_gecko_prefs_value_hint,
                engineOnly = true,
            ),
            SettingDefinition.StringListSetting(
                SettingField(WebAppSettings::sameAppDomains, null),
                R.string.setting_same_app_domains,
                R.string.setting_same_app_domains_desc,
                SettingCategory.NAVIGATION,
            ),
            SettingDefinition.StringListSetting(
                SettingField(WebAppSettings::blockedDomains, null),
                R.string.setting_blocked_domains,
                R.string.setting_blocked_domains_desc,
                SettingCategory.NAVIGATION,
            ),
            SettingDefinition.StringListSetting(
                SettingField(WebAppSettings::skipHistoryDomains, null),
                R.string.setting_skip_history_domains,
                R.string.setting_skip_history_domains_desc,
                SettingCategory.NAVIGATION,
            ),
        )

    val all: List<SettingDefinition>
        get() = ALL_SETTINGS

    val perApp: List<SettingDefinition>
        get() = ALL_SETTINGS.filter { !it.engineOnly }

    val engine: List<SettingDefinition>
        get() = ALL_SETTINGS.filter { it.engineOnly }

    fun forSection(section: SettingSection): List<SettingDefinition> =
        when (section) {
            SettingSection.GLOBAL -> perApp
            SettingSection.ENGINE -> engine
        }

    fun byKey(key: String): SettingDefinition? = ALL_SETTINGS.find { it.key == key }

    private val FIELDS_BY_KEY: Map<String, SettingField> by lazy {
        ALL_SETTINGS.flatMap { it.allFields }.associateBy { it.key }
    }

    fun fieldByKey(key: String): SettingField? = FIELDS_BY_KEY[key]
}
