package wtf.mazy.peel.webview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse

class PeelContentDelegate(
    private val host: SessionHost,
    private val onDownload: (WebResponse) -> Unit,
    private val onContextMenu: ((GeckoSession, Int, Int, GeckoSession.ContentDelegate.ContextElement) -> Unit)? = null,
) : GeckoSession.ContentDelegate {

    private var customView: View? = null
    private var originalOrientation = 0

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
}
