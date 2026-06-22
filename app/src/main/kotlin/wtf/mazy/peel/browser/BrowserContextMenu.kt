package wtf.mazy.peel.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.LinearLayout
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R
import wtf.mazy.peel.util.BrowserLauncher
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.shouldOfferOpenInSystem
import wtf.mazy.peel.util.shareText

class BrowserContextMenu(
    private val activity: Context,
    private val downloadHandler: DownloadHandler,
    private val onExternalIntent: (Uri) -> Unit,
    private val peelMatch: PeelMatchHooks,
) {

    data class PeelMatchHooks(
        val icon: (String) -> Bitmap?,
        val openBestMatch: (String) -> Unit,
        val openPicker: (String) -> Unit,
    )

    fun onContextMenu(
        element: GeckoSession.ContentDelegate.ContextElement,
    ) {
        val info = resolveHitInfo(element) ?: return
        if (!info.hasContent) return
        showDialog(info)
    }

    private data class MenuAction(
        val label: String,
        val icon: Bitmap? = null,
        val onIconClick: (() -> Unit)? = null,
        val handler: () -> Unit,
    )

    private data class HitInfo(
        val title: String?,
        val url: String,
        val linkActions: List<MenuAction>,
        val imageActions: List<MenuAction>,
    ) {
        val hasContent get() = linkActions.isNotEmpty() || imageActions.isNotEmpty()
    }

    private fun resolveHitInfo(element: GeckoSession.ContentDelegate.ContextElement): HitInfo? {
        val linkUrl = element.linkUri
        val srcUrl = element.srcUri
        val title = (element.linkText?.takeIf { it.isNotBlank() } ?: element.title)
            ?.takeIf { it.isNotBlank() }

        val mediaActions = when {
            srcUrl == null -> emptyList()
            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE ->
                imageActionsFor(srcUrl)

            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ->
                videoActionsFor(srcUrl)

            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO ->
                audioActionsFor(srcUrl)

            else -> emptyList()
        }
        val linkActions = if (linkUrl != null) linkActionsFor(linkUrl, title) else emptyList()

        if (linkActions.isEmpty() && mediaActions.isEmpty()) return null

        val displayUrl = linkUrl ?: srcUrl ?: return null
        return HitInfo(
            title = title?.takeIf { it != displayUrl },
            url = displayUrl,
            linkActions = linkActions,
            imageActions = mediaActions,
        )
    }

    private fun linkActionsFor(url: String, title: String?) = buildList {
        val icon = peelMatch.icon(url)
        val iconClick = if (icon != null) ({ peelMatch.openBestMatch(url) }) else null
        add(MenuAction(str(R.string.open_in_peel), icon, iconClick) { peelMatch.openPicker(url) })
        if (activity.shouldOfferOpenInSystem(url)) {
            add(MenuAction(str(R.string.open_in_system)) { onExternalIntent(url.toUri()) })
        }
        add(MenuAction(str(R.string.open_incognito_action)) {
            BrowserLauncher.launchIncognito(activity, url)
        })
        add(MenuAction(str(R.string.context_menu_share_link)) { activity.shareText(url, title) })
        add(MenuAction(str(R.string.context_menu_copy_link)) { activity.copyToClipboard(url) })
        if (title != null) {
            add(MenuAction(str(R.string.context_menu_copy_link_text)) {
                activity.copyToClipboard(title, R.string.text_copied)
            })
        }
    }

    private fun imageActionsFor(url: String) = listOf(
        MenuAction(str(R.string.context_menu_download_image)) { downloadHandler.downloadUrl(url) },
        MenuAction(str(R.string.context_menu_share_image)) { shareImage(url) },
    )

    private fun videoActionsFor(url: String) = listOf(
        MenuAction(str(R.string.context_menu_copy_video_url)) { activity.copyToClipboard(url) },
    )

    private fun audioActionsFor(url: String) = listOf(
        MenuAction(str(R.string.context_menu_copy_audio_url)) { activity.copyToClipboard(url) },
    )

    private fun showDialog(info: HitInfo) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun dismiss(action: () -> Unit): () -> Unit = { dialog?.dismiss(); action() }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(MenuDialogHelper.buildDivider(activity))
            val sections = listOf(info.linkActions, info.imageActions).filter { it.isNotEmpty() }
            sections.forEachIndexed { i, actions ->
                if (i > 0) addView(MenuDialogHelper.buildDivider(activity))
                actions.forEach { action ->
                    addView(
                        MenuDialogHelper.buildActionRow(
                            activity,
                            action.label,
                            action.icon,
                            action.onIconClick?.let { dismiss(it) },
                            dismiss(action.handler),
                        )
                    )
                }
            }
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setCustomTitle(
                MenuDialogHelper.buildHeader(
                    activity,
                    info.title,
                    MenuDialogHelper.prettyDataUrl(info.url),
                )
            )
            .setView(content)
            .show()
    }

    private fun shareImage(url: String) {
        downloadHandler.shareImage(url)
    }

    private fun str(resId: Int): String = activity.getString(resId)

}
