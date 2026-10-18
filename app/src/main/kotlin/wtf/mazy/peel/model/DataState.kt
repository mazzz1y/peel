package wtf.mazy.peel.model

data class DataState(
    val webApps: List<WebApp>,
    val groups: List<WebAppGroup>,
    val globalSettings: WebApp,
    val proxies: List<Proxy> = emptyList(),
) {
    val proxyUuids: Set<String>
        get() = proxies.mapTo(mutableSetOf()) { it.uuid }

    val ephemeralContextIds: Set<String>
        get() = buildSet {
            webApps.filter { it.isolation.isEphemeral }.mapTo(this) { it.uuid }
            groups.filter { it.isolation.isEphemeral }.mapTo(this) { it.uuid }
        }
}
