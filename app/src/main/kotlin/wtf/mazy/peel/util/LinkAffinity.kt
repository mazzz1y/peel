package wtf.mazy.peel.util

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.model.WebApp

fun belongsToApp(origin: String, url: String, settings: EffectiveSettings): Boolean {
    val base = settings.upgradeUrl(origin)
    return isSameHost(base, url) || linkMatch(base, url, settings).belongs
}

fun linkMatch(appAddress: String, url: String, settings: EffectiveSettings): LinkMatch =
    linkMatch(appAddress, url) { SameAppDomainMatcher.matching(it, settings.sameAppDomains) }

/** The web app's address as it opens, after its HTTPS upgrade. */
val WebApp.address: String get() = DataManager.effectiveSettings(this).upgradeUrl(baseUrl)

fun WebApp.linkMatch(url: String): LinkMatch =
    linkMatch(address, url, DataManager.effectiveSettings(this))

fun List<WebApp>.sortedByAffinity(url: String): List<WebApp> {
    val matches = associateWith { it.linkMatch(url) }
    return sortedWith(compareByDescending<WebApp> { matches.getValue(it) }.thenBy { it.title })
}
