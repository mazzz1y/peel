package wtf.mazy.peel.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R

class WebViewContextMenu(
    private val activity: Context,
    private val downloadHandler: DownloadHandler,
    private val onExternalIntent: (Uri) -> Unit,
    private val onOpenInPeel: ((String) -> Unit)?,
    private val onOpenInBestPeelMatch: ((String) -> Unit)?,
    private val bestPeelMatchIcon: ((String) -> Bitmap?)?,
    private val onToast: (String) -> Unit,
) {

    fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
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
            addView(buildHeader(info.title, displayUrl(info.url)))
            addView(buildDivider())
            val sections = listOf(info.linkActions, info.imageActions).filter { it.isNotEmpty() }
            sections.forEachIndexed { i, actions ->
                if (i > 0) addView(buildDivider())
                actions.forEach { action ->
                    addView(
                        buildActionRow(
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

    private fun buildHeader(title: String?, url: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(16f), dpToPx(20f), dpToPx(16f), dpToPx(14f))
        if (title != null) {
            addView(TextView(activity).apply {
                text = title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
            })
        }
        addView(TextView(activity).apply {
            text = url
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            if (title != null) setPadding(0, dpToPx(2f), 0, 0)
        })
    }

    private fun buildDivider() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f),
        )
        setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant))
    }

    private fun buildActionRow(
        label: String,
        icon: Bitmap? = null,
        onIconClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        if (icon == null || onIconClick == null) {
            return TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dpToPx(16f), dpToPx(14f), dpToPx(16f), dpToPx(14f))
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dpToPx(16f), dpToPx(14f), dpToPx(12f), dpToPx(14f))
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            })

            val iconSize = dpToPx(24f)
            val iconPad = dpToPx(16f)
            addView(ImageView(activity).apply {
                setImageBitmap(icon)
                layoutParams = LinearLayout.LayoutParams(
                    iconSize + iconPad * 2,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
                setPadding(iconPad, 0, iconPad, 0)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onIconClick() }
            })
        }
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

    private fun displayUrl(url: String): String {
        if (url.startsWith("data:")) return url.substringBefore(",").substringAfter("data:")
        return stripQueryParams(url)
    }

    private fun stripQueryParams(url: String): String {
        val q = url.indexOf('?')
        return if (q > 0) url.substring(0, q) else url
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        activity.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, activity.resources.displayMetrics,
    ).toInt()
}
