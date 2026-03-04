package wtf.mazy.peel.webview

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import wtf.mazy.peel.R
import java.io.File

class WebViewContextMenu(
    private val activity: Context,
    private val getWebView: () -> WebView?,
    private val imageCache: ImageCache,
    private val onExternalIntent: (Uri) -> Unit,
    private val onToast: (String) -> Unit,
) {

    fun install(webView: WebView) {
        webView.setOnLongClickListener { show(webView) }
    }

    private fun show(webView: WebView): Boolean {
        val info = resolveHitInfo(webView) ?: return false
        if (!info.hasContent) return false
        showDialog(info)
        return true
    }

    private data class MenuAction(val label: String, val handler: () -> Unit)

    private data class HitInfo(
        val title: String?,
        val url: String,
        val linkActions: List<MenuAction>,
        val imageActions: List<MenuAction>,
    ) {
        val hasContent get() = linkActions.isNotEmpty() || imageActions.isNotEmpty()
    }

    private fun resolveHitInfo(webView: WebView): HitInfo? {
        val hit = webView.hitTestResult
        val extra = hit.extra ?: return null

        val nodeHref = when (hit.type) {
            HitTestResult.SRC_ANCHOR_TYPE,
            HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()).obtainMessage()
                webView.requestFocusNodeHref(msg)
                msg.data
            }
            else -> null
        }
        val title = nodeHref?.getString("title")?.takeIf { it.isNotBlank() }

        return when (hit.type) {
            HitTestResult.SRC_ANCHOR_TYPE ->
                HitInfo(title?.takeIf { it != extra }, extra, linkActionsFor(extra, title), emptyList())

            HitTestResult.IMAGE_TYPE ->
                HitInfo(null, extra, emptyList(), imageActionsFor(extra))

            HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val anchorUrl = nodeHref?.getString("url")
                val displayUrl = anchorUrl ?: extra
                HitInfo(
                    title = title?.takeIf { it != displayUrl },
                    url = displayUrl,
                    linkActions = anchorUrl?.let { linkActionsFor(it, title) } ?: emptyList(),
                    imageActions = imageActionsFor(extra),
                )
            }

            else -> null
        }
    }

    private fun linkActionsFor(url: String, title: String?) = buildList {
        add(MenuAction(str(R.string.context_menu_open_link)) { onExternalIntent(url.toUri()) })
        add(MenuAction(str(R.string.context_menu_copy_link)) { copyToClipboard(url) })
        if (title != null) {
            add(MenuAction(str(R.string.context_menu_copy_link_text)) {
                copyToClipboard(title, R.string.text_copied)
            })
        }
        add(MenuAction(str(R.string.context_menu_download_link)) { downloadUrl(url) })
        add(MenuAction(str(R.string.context_menu_share_link)) { shareText(url, title) })
    }

    private fun imageActionsFor(url: String) = listOf(
        MenuAction(str(R.string.context_menu_copy_image)) { copyImage(url) },
        MenuAction(str(R.string.context_menu_download_image)) { downloadUrl(url) },
        MenuAction(str(R.string.context_menu_share_image)) { shareImage(url) },
    )

    private fun showDialog(info: HitInfo) {
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildHeader(info.title, displayUrl(info.url)))
            addView(buildDivider())
            val sections = listOf(info.linkActions, info.imageActions).filter { it.isNotEmpty() }
            sections.forEachIndexed { i, actions ->
                if (i > 0) addView(buildDivider())
                actions.forEach { action ->
                    addView(buildActionRow(action.label) { dialog?.dismiss(); action.handler() })
                }
            }
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setView(content)
            .show()
    }

    private fun buildHeader(title: String?, url: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(24f), dpToPx(20f), dpToPx(24f), dpToPx(14f))
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

    private fun buildActionRow(label: String, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
        setPadding(dpToPx(24f), dpToPx(14f), dpToPx(24f), dpToPx(14f))
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
        setOnClickListener { onClick() }
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

    private fun downloadUrl(url: String) {
        if (!hasStoragePermission()) return
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            withImageFile(url) { file ->
                val target = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    file.name,
                )
                file.copyTo(target, overwrite = true)
                onToast(str(R.string.file_download))
            }
            return
        }
        val uri = url.toUri()
        val fileName = uri.lastPathSegment ?: "download"
        val request = DownloadManager.Request(uri).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
            addRequestHeader("User-Agent", getWebView()?.settings?.userAgentString ?: "")
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.enqueue(request)
        onToast(str(R.string.file_download))
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun withImageFile(url: String, action: (File) -> Unit) {
        imageCache.fetch(url, null) { file ->
            if (file != null) action(file)
            else onToast(str(R.string.image_download_failed))
        }
    }

    private fun imageContentUri(file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

    private fun imageMimeType(file: File): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension)
            ?: "image/*"

    private fun copyImage(url: String) = withImageFile(url) { file ->
        val uri = imageContentUri(file)
        val mime = imageMimeType(file)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData("image", arrayOf(mime), ClipData.Item(uri)))
        onToast(str(R.string.image_copied))
    }

    private fun shareImage(url: String) = withImageFile(url) { file ->
        val uri = imageContentUri(file)
        val mime = imageMimeType(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (url.startsWith("http")) putExtra(Intent.EXTRA_TEXT, stripQueryParams(url))
            clipData = ClipData.newUri(activity.contentResolver, null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, null))
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
