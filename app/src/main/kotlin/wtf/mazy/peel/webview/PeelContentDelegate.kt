package wtf.mazy.peel.webview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import wtf.mazy.peel.gecko.GeckoRuntimeProvider

class PeelContentDelegate(
    private val host: SessionHost,
    private val onDownload: (WebResponse) -> Unit,
    private val onContextMenu: ((GeckoSession, Int, Int, GeckoSession.ContentDelegate.ContextElement) -> Unit)? = null,
) : GeckoSession.ContentDelegate {

    private var customView: View? = null
    private var originalOrientation = 0

    fun setupThemeColorExtension(ext: WebExtension, session: GeckoSession) {
        Log.d(TAG, "setupThemeColorExtension: id=${ext.id} flags=${ext.flags}")
        if (host.effectiveSettings.isDynamicStatusBar != true) return
        session.webExtensionController.setMessageDelegate(
            ext,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    if (message is JSONObject) {
                        val raw = message.optString("color", "")
                        Log.d(TAG, "onMessage color='$raw'")
                        val color = parseWebColor(raw) ?: host.themeBackgroundColor
                        host.runOnUi { host.updateStatusBarColor(color) }
                    }
                    return null
                }
            },
            GeckoRuntimeProvider.THEME_COLOR_APP,
        )
    }

    override fun onTitleChange(session: GeckoSession, title: String?) {}

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        if (fullScreen) {
            originalOrientation = host.hostOrientation
            host.hideSystemBars()
        } else {
            if (customView != null) {
                (host.hostWindow.decorView as FrameLayout).removeView(customView)
                customView = null
            }
            host.hostOrientation = originalOrientation
            host.showSystemBars()
        }
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        onDownload(response)
    }

    override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement,
    ) {
        onContextMenu?.invoke(session, screenX, screenY, element)
    }

    override fun onFirstContentfulPaint(session: GeckoSession) {
        host.onFirstContentfulPaint()
    }

    fun getDefaultVideoPoster(): Bitmap {
        val bitmap = createBitmap(1, 1)
        Canvas(bitmap).drawARGB(0, 0, 0, 0)
        return bitmap
    }

    companion object {
        private const val TAG = "PeelColor"

        private val rgbRegex = Regex("""rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)""")

        fun parseWebColor(raw: String): Int? {
            if (raw.isBlank()) return null
            val normalized = normalizeHexColor(raw)
            try {
                val color = normalized.toColorInt()
                if (Color.alpha(color) == 0) return null
                return color
            } catch (_: IllegalArgumentException) {}
            rgbRegex.find(raw)?.let { match ->
                val r = match.groupValues[1].toIntOrNull() ?: return null
                val g = match.groupValues[2].toIntOrNull() ?: return null
                val b = match.groupValues[3].toIntOrNull() ?: return null
                return Color.rgb(r, g, b)
            }
            return null
        }

        private fun normalizeHexColor(color: String): String {
            if (!color.startsWith('#')) return color
            val hex = color.substring(1)
            return when (hex.length) {
                3 -> "#${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                4 -> "#${hex[3]}${hex[3]}${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                8 -> "#${hex.substring(6, 8)}${hex.take(6)}"
                else -> color
            }
        }
    }
}
