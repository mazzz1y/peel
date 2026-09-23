package wtf.mazy.peel.gecko

enum class ExtensionStateEvent(val requiresReload: Boolean) {
    ADDED(true),
    REMOVED(true),
    TOGGLED(false),
}

fun interface ExtensionStateListener {
    fun onExtensionStateChanged(event: ExtensionStateEvent)
}
