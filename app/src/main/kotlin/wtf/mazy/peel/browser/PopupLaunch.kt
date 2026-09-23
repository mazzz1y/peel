package wtf.mazy.peel.browser

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json
import wtf.mazy.peel.model.WebAppSettings
import wtf.mazy.peel.util.ActivityRoutes

object PopupLaunch {
    const val EXTRA_SESSION_KEY = "popup_session_key"
    const val EXTRA_TITLE = "popup_title"
    const val EXTRA_SETTINGS = "popup_settings"
    const val EXTRA_CONTEXT_ID = "popup_context_id"
    const val EXTRA_PRIVATE_MODE = "popup_private_mode"
    const val EXTRA_OWNER_UUID = "popup_owner_uuid"
    const val EXTRA_TRANSLATE_TARGET = "popup_translate_target"
    const val EXTRA_POLICY_ORIGIN = "popup_policy_origin"

    fun intent(
        context: Context,
        key: String,
        title: String,
        settings: WebAppSettings,
        contextId: String?,
        privateMode: Boolean,
        ownerWebAppUuid: String?,
        translateTarget: String?,
        policyOrigin: String,
    ): Intent =
        Intent(context, ActivityRoutes.popup)
            .putExtra(EXTRA_SESSION_KEY, key)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SETTINGS, Json.encodeToString(settings))
            .putExtra(EXTRA_CONTEXT_ID, contextId)
            .putExtra(EXTRA_PRIVATE_MODE, privateMode)
            .putExtra(EXTRA_OWNER_UUID, ownerWebAppUuid)
            .putExtra(EXTRA_TRANSLATE_TARGET, translateTarget)
            .putExtra(EXTRA_POLICY_ORIGIN, policyOrigin)
}
