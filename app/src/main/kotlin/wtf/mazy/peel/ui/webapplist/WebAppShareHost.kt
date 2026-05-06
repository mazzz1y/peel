package wtf.mazy.peel.ui.webapplist

import wtf.mazy.peel.model.WebApp

interface WebAppShareHost {
    fun shareApps(webApps: List<WebApp>, includeSecrets: Boolean)
    fun refreshWebAppList()
}
