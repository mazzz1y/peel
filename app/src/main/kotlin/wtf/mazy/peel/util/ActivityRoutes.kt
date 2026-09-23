package wtf.mazy.peel.util

import android.app.Activity

object ActivityRoutes {
    lateinit var browser: Class<out Activity>
        private set
    lateinit var popup: Class<out Activity>
        private set
    lateinit var extensionPage: Class<out Activity>
        private set
    lateinit var linkRouter: Class<out Activity>
        private set
    lateinit var trampoline: Class<out Activity>
        private set
    lateinit var webAppSettings: Class<out Activity>
        private set

    fun install(
        browser: Class<out Activity>,
        popup: Class<out Activity>,
        extensionPage: Class<out Activity>,
        linkRouter: Class<out Activity>,
        trampoline: Class<out Activity>,
        webAppSettings: Class<out Activity>,
    ) {
        this.browser = browser
        this.popup = popup
        this.extensionPage = extensionPage
        this.linkRouter = linkRouter
        this.trampoline = trampoline
        this.webAppSettings = webAppSettings
    }
}
