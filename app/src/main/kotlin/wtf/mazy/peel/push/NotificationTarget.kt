package wtf.mazy.peel.push

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSandbox
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.dialog.ExternalLinkMenu
import wtf.mazy.peel.util.WebOrigin

/** The web app a web notification, or a window its site opens, belongs to. */
object NotificationTarget {

    fun forSandbox(contextId: String?, url: String): WebApp? =
        resolve(contextId, url, DataManager.webApps, DataManager::effectiveSandbox, ::address, ::bestMatch)

    fun forAddress(url: String): WebApp? {
        val apps = DataManager.webApps.filter { DataManager.effectiveSandbox(it) !is EffectiveSandbox.Private }
        return byPlace(apps, url, ::address, ::bestMatch)
    }

    fun isInSandbox(webApp: WebApp, contextId: String?): Boolean =
        sharesSandbox(DataManager.effectiveSandbox(webApp), contextId)

    fun resolve(
        contextId: String?,
        url: String,
        apps: List<WebApp>,
        sandboxOf: (WebApp) -> EffectiveSandbox,
        addressOf: (WebApp) -> String,
        bestMatch: (List<WebApp>, String) -> WebApp?,
    ): WebApp? {
        val owner = contextId?.let { id ->
            apps.find { it.uuid == id && sandboxOf(it) is EffectiveSandbox.Own }
        }
        if (owner != null) return owner
        val candidates = apps.filter { sharesSandbox(sandboxOf(it), contextId) }
        return byPlace(candidates, url, addressOf, bestMatch)
    }

    private fun byPlace(
        apps: List<WebApp>,
        url: String,
        addressOf: (WebApp) -> String,
        bestMatch: (List<WebApp>, String) -> WebApp?,
    ): WebApp? = samePlace(apps, url, addressOf) ?: bestMatch(apps, url)

    private fun samePlace(apps: List<WebApp>, url: String, addressOf: (WebApp) -> String): WebApp? {
        val origin = WebOrigin.of(url) ?: return null
        return apps.singleOrNull { app ->
            WebOrigin.of(addressOf(app))?.isSamePlaceAs(origin) == true
        }
    }

    private fun sharesSandbox(sandbox: EffectiveSandbox, contextId: String?): Boolean =
        sandbox !is EffectiveSandbox.Private && sandbox.contextId == contextId

    private fun address(webApp: WebApp): String =
        DataManager.effectiveSettings(webApp).upgradeUrl(webApp.baseUrl)

    private fun bestMatch(apps: List<WebApp>, url: String): WebApp? =
        ExternalLinkMenu.bestPeelMatch(apps, url, excludeUuid = null)
}
