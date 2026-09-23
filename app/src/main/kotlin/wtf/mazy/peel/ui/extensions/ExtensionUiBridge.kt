package wtf.mazy.peel.ui.extensions

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.gecko.ExtensionStateEvent
import wtf.mazy.peel.gecko.ExtensionUiHooks
import wtf.mazy.peel.util.ForegroundActivityTracker

object ExtensionUiBridge : ExtensionUiHooks {

    override suspend fun confirmPermissions(
        ext: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        titleRes: Int,
        summaryRes: Int,
        positiveRes: Int,
        showEvenIfEmpty: Boolean,
    ): Boolean {
        val activity = ForegroundActivityTracker.current as? AppCompatActivity ?: return false
        return ExtensionPermissionPrompt.confirm(
            activity = activity,
            title = activity.getString(titleRes),
            summaryRes = summaryRes,
            ext = ext,
            permissions = permissions,
            origins = origins,
            showEvenIfEmpty = showEvenIfEmpty,
            positiveRes = positiveRes,
        )
    }

    override suspend fun onExtensionInstalled(context: Context, extension: WebExtension) =
        ExtensionIconCache.refreshFromExtension(context, extension)

    override fun onExtensionUninstalled(context: Context, extension: WebExtension) =
        ExtensionIconCache.delete(context, extension.id)

    override fun onUserExtensionsListed(extensions: List<WebExtension>) =
        SessionExtensionActions.ensureExtensionDelegatesRegistered(extensions)

    override fun onExtensionStateChanged(event: ExtensionStateEvent) {
        SessionExtensionActions.extensionsChanged = true
    }
}
