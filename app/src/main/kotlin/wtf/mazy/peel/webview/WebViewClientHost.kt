package wtf.mazy.peel.webview

import android.net.Uri
import android.webkit.HttpAuthHandler
import wtf.mazy.peel.model.WebAppSettings

interface WebViewClientHost {
    val effectiveSettings: WebAppSettings
    val baseUrl: String
    val webappUuid: String?
    var urlOnFirstPageload: String

    fun showNotification()

    fun showHttpAuthDialog(handler: HttpAuthHandler, host: String?, realm: String?)

    fun setDarkModeIfNeeded()

    fun loadURL(url: String)

    fun finishActivity()

    fun showToast(message: String)

    fun updateStatusBarColor(color: Int)

    fun startExternalIntent(uri: Uri)

    fun runOnUi(action: Runnable)

    fun getString(resId: Int): String

    fun getString(resId: Int, vararg formatArgs: Any): String
}
