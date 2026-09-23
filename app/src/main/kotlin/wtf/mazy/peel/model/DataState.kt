package wtf.mazy.peel.model

data class DataState(
    val webApps: List<WebApp>,
    val groups: List<WebAppGroup>,
    val globalSettings: WebApp,
    val proxies: List<Proxy> = emptyList(),
)
