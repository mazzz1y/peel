package wtf.mazy.peel.util

import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.WebAppSettings

fun isSameHost(origin: String, url: String): Boolean {
    val originHost = origin.normalizedHost() ?: return false
    return originHost == url.normalizedHost()
}

fun belongsToApp(origin: String, url: String, settings: WebAppSettings): Boolean {
    val base = settings.upgradeUrl(origin)
    if (isSameHost(base, url)) return true
    return linkAffinity(base, url, settings.sameAppDomains) > HostIdentity.TLD_ONLY
}

fun linkAffinity(baseUrl: String, url: String, sameAppDomains: List<String>?): Int {
    val heuristic = HostIdentity.affinity(baseUrl, url)
    if (!SameAppDomainMatcher.matches(url, sameAppDomains.orEmpty())) return heuristic
    return maxOf(heuristic, HostIdentity.sameAppDomainAffinity(url))
}

fun WebApp.linkAffinity(url: String): Int =
    linkAffinity(baseUrl, url, DataManager.instance.resolveEffectiveSettings(this).sameAppDomains)

fun List<WebApp>.sortedByAffinity(url: String): List<WebApp> {
    val scores = associateWith { it.linkAffinity(url) }
    return sortedWith(compareByDescending<WebApp> { scores.getValue(it) }.thenBy { it.title })
}
