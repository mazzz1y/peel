package wtf.mazy.peel.model

data class DataState(
    val websites: List<WebApp>,
    val groups: List<WebAppGroup>,
    val defaultSettings: WebApp,
)
