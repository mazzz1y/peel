package wtf.mazy.peel.gecko

import android.content.Context
import androidx.annotation.StringRes
import org.mozilla.geckoview.WebExtension

interface ExtensionUiHooks {
    suspend fun confirmPermissions(
        ext: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        @StringRes positiveRes: Int,
        showEvenIfEmpty: Boolean,
    ): Boolean

    suspend fun onExtensionInstalled(context: Context, extension: WebExtension)

    fun onExtensionUninstalled(context: Context, extension: WebExtension)

    fun onUserExtensionsListed(extensions: List<WebExtension>)

    fun onExtensionStateChanged(event: ExtensionStateEvent)
}
