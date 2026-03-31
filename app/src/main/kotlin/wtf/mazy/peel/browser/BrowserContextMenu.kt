package wtf.mazy.peel.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.LinearLayout
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R

class BrowserContextMenu(
    private val activity: Context,
    private val downloadHandler: DownloadHandler,
    private val onExternalIntent: (Uri) -> Unit,
    private val onOpenInPeel: ((String) -> Unit)?,
    private val onOpenInBestPeelMatch: ((String) -> Unit)?,
    private val bestPeelMatchIcon: ((String) -> Bitmap?)?,
    private val onToast: (String) -> Unit,
) {

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
        val imageUrl = element.srcUri
        val title = (element.linkText?.takeIf { it.isNotBlank() } ?: element.title)
            ?.takeIf { it.isNotBlank() }

        return when {
            linkUrl != null && imageUrl != null -> HitInfo(
                title = title?.takeIf { it != linkUrl },
                url = linkUrl,
                linkActions = linkActionsFor(linkUrl, title),
                imageActions = imageActionsFor(imageUrl),
            )

            linkUrl != null -> HitInfo(
                title = title?.takeIf { it != linkUrl },
                url = linkUrl,
                linkActions = linkActionsFor(linkUrl, title),
                imageActions = emptyList(),
            )

            imageUrl != null -> HitInfo(
                title = null,
                url = imageUrl,
                linkActions = emptyList(),
                imageActions = imageActionsFor(imageUrl),
            )

            else -> null
        }
    }

    private fun linkActionsFor(url: String, title: String?) = buildList {
        if (onOpenInPeel != null) {
            val icon = bestPeelMatchIcon?.invoke(url)
            val iconClick = if (icon != null) onOpenInBestPeelMatch?.let { { it(url) } } else null
            add(MenuAction(str(R.string.open_in_peel), icon, iconClick) { onOpenInPeel(url) })
        }
        add(MenuAction(str(R.string.context_menu_open_link)) { onExternalIntent(url.toUri()) })
        add(MenuAction(str(R.string.context_menu_share_link)) { shareText(url, title) })
        add(MenuAction(str(R.string.context_menu_copy_link)) { copyToClipboard(url) })
        if (title != null) {
            add(MenuAction(str(R.string.context_menu_copy_link_text)) {
                copyToClipboard(title, R.string.text_copied)
            })
        }
    }

    private fun imageActionsFor(url: String) = listOf(
        MenuAction(str(R.string.context_menu_download_image)) { downloadHandler.downloadUrl(url) },
        MenuAction(str(R.string.context_menu_share_image)) { shareImage(url) },
    )

    private fun showDialog(info: HitInfo) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun dismiss(action: () -> Unit): () -> Unit = { dialog?.dismiss(); action() }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                MenuDialogHelper.buildHeader(
                    activity,
                    info.title,
                    MenuDialogHelper.displayUrl(info.url)
                )
            )
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
            .setView(content)
            .show()
    }

    private fun copyToClipboard(text: String, toastResId: Int = R.string.link_copied) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        onToast(str(toastResId))
    }

    private fun shareText(text: String, title: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title ?: text)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    private fun shareImage(url: String) {
        downloadHandler.shareImage(url)
    }

    private fun str(resId: Int): String = activity.getString(resId)

}
