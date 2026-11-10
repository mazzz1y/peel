package wtf.mazy.peel.push

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSandbox
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.bestFor
import wtf.mazy.peel.util.WebOrigin
import wtf.mazy.peel.util.address

/** The web app a web notification, or a window its site opens, belongs to. */
object NotificationTarget {

    fun forSandbox(contextId: String?, url: String): WebApp? =
        resolve(contextId, url, DataManager.webApps, DataManager::effectiveSandbox, { it.address }, List<WebApp>::bestFor)

    fun forAddress(url: String): WebApp? {
        val apps = DataManager.webApps.filter { DataManager.effectiveSandbox(it) !is EffectiveSandbox.Private }
        return byPlace(apps, url, { it.address }, List<WebApp>::bestFor)
    }

    internal fun resolve(
        contextId: String?,
        url: String,
        apps: List<WebApp>,
        sandboxOf: (WebApp) -> EffectiveSandbox,
        addressOf: (WebApp) -> String,
        heuristic: (List<WebApp>, String) -> WebApp?,
    ): WebApp? {
        val owner = contextId?.let { id ->
            apps.find { it.uuid == id && sandboxOf(it) is EffectiveSandbox.Own }
        }
        if (owner != null) return owner
        val candidates = apps.filter { sandboxOf(it).holds(contextId) }
        return byPlace(candidates, url, addressOf, heuristic)
    }

    private fun byPlace(
        apps: List<WebApp>,
        url: String,
        addressOf: (WebApp) -> String,
        heuristic: (List<WebApp>, String) -> WebApp?,
    ): WebApp? = samePlace(apps, url, addressOf) ?: heuristic(apps, url)

    private fun samePlace(apps: List<WebApp>, url: String, addressOf: (WebApp) -> String): WebApp? {
        val origin = WebOrigin.of(url) ?: return null
        return apps.singleOrNull { app ->
            WebOrigin.of(addressOf(app))?.isSamePlaceAs(origin) == true
        }
    }
}
