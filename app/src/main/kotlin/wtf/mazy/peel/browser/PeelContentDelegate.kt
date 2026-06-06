package wtf.mazy.peel.browser

import android.graphics.Color
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

    private var originalOrientation = 0
    private var isFullscreen = false

    fun exitFullscreen() {
        if (!isFullscreen) return
        restoreFromFullscreen()
    }

    fun setupThemeColorExtension(ext: WebExtension, session: GeckoSession) {
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
                        val topCandidates = parseColorArray(message.optJSONArray("top"))
                        val bottomCandidates = parseColorArray(message.optJSONArray("bottom"))
                        val metaThemeColor = parseWebColor(message.optString("meta", ""))
                        host.runOnUi {
                            host.reportSystemBarColorsFromContent(
                                topCandidates,
                                bottomCandidates,
                                metaThemeColor,
                            )
                        }
                    }
                    return null
                }
            },
            GeckoRuntimeProvider.THEME_COLOR_APP,
        )
    }

    private fun parseColorArray(array: org.json.JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val result = ArrayList<Int>(array.length())
        for (i in 0 until array.length()) {
            parseWebColor(array.optString(i, ""))?.let { result.add(it) }
        }
        return result
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        if (fullScreen) {
            isFullscreen = true
            originalOrientation = host.hostOrientation
            host.onWebFullscreenEnter()
        } else {
            restoreFromFullscreen()
        }
    }

    private fun restoreFromFullscreen() {
        isFullscreen = false
        host.hostOrientation = originalOrientation
        host.onWebFullscreenExit()
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        host.markCurrentPageAsJumpHost()
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

    override fun onCloseRequest(session: GeckoSession) {
        host.runOnUi { host.onWindowCloseRequest() }
    }

    override fun onKill(session: GeckoSession) {
        host.runOnUi { host.onProcessKilled() }
    }

    override fun onCrash(session: GeckoSession) {
        host.runOnUi { host.onContentCrashed() }
    }

    companion object {
        private val rgbRegex = Regex(
            """rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)(?:[\s,]+(\d*\.?\d+))?"""
        )

        fun parseWebColor(raw: String): Int? {
            if (raw.isBlank()) return null
            val normalized = normalizeHexColor(raw)
            try {
                val color = normalized.toColorInt()
                if (Color.alpha(color) == 0) return null
                return color
            } catch (_: IllegalArgumentException) {
            }
            rgbRegex.find(raw)?.let { match ->
                val r = match.groupValues[1].toIntOrNull() ?: return null
                val g = match.groupValues[2].toIntOrNull() ?: return null
                val b = match.groupValues[3].toIntOrNull() ?: return null
                val aStr = match.groupValues.getOrNull(4)
                val a = if (aStr.isNullOrEmpty()) 255 else {
                    val parsed = aStr.toFloatOrNull() ?: return null
                    (parsed.coerceIn(0f, 1f) * 255f).toInt()
                }
                if (a == 0) return null
                return Color.argb(a, r, g, b)
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
