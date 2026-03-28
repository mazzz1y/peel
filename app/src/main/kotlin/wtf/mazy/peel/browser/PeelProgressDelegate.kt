package wtf.mazy.peel.browser

import android.widget.ProgressBar
import androidx.core.view.isGone
import org.mozilla.geckoview.GeckoSession

class PeelProgressDelegate(private val host: SessionHost) : GeckoSession.ProgressDelegate {

    override fun onPageStart(session: GeckoSession, url: String) {
        host.onPageStarted()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        host.hostProgressBar?.let { bar ->
            bar.visibility = ProgressBar.GONE
            host.currentlyReloading = false
        }
        if (success) {
            host.onPageFullyLoaded()
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        if (host.effectiveSettings.isShowProgressbar == true || host.currentlyReloading) {
            host.hostProgressBar?.let { bar ->
                if (bar.isGone && progress < 100) bar.visibility = ProgressBar.VISIBLE
                bar.progress = progress
                if (progress == 100) {
                    bar.visibility = ProgressBar.GONE
                    host.currentlyReloading = false
                }
            }
        }
        if (progress == 100) {
            host.onPageFullyLoaded()
        }
    }

}
