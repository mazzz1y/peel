package wtf.mazy.peel.util

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.EffectiveSettings
import wtf.mazy.peel.model.WebApp

fun isSameHost(origin: String, url: String): Boolean {
    val originHost = origin.normalizedHost() ?: return false
    return originHost == url.normalizedHost()
}

fun belongsToApp(origin: String, url: String, settings: EffectiveSettings): Boolean {
    val base = settings.upgradeUrl(origin)
    return isSameHost(base, url) || linkMatch(base, url, settings).belongs
}

fun linkMatch(appAddress: String, url: String, settings: EffectiveSettings): LinkMatch =
    linkMatch(appAddress, url) { SameAppDomainMatcher.matching(it, settings.sameAppDomains) }

fun WebApp.linkMatch(url: String): LinkMatch {
    val settings = DataManager.effectiveSettings(this)
    return linkMatch(settings.upgradeUrl(baseUrl), url, settings)
}

fun List<WebApp>.sortedByAffinity(url: String): List<WebApp> {
    val matches = associateWith { it.linkMatch(url) }
    return sortedWith(compareByDescending<WebApp> { matches.getValue(it) }.thenBy { it.title })
}
