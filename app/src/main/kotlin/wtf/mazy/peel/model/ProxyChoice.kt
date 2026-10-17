package wtf.mazy.peel.model

sealed interface ProxyChoice {
    data object Inherit : ProxyChoice
}

sealed interface ProxyRoute : ProxyChoice {
    data object Direct : ProxyRoute

    data class Via(val proxyUuid: String) : ProxyRoute
}
