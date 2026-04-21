package wtf.mazy.peel.gecko

enum class ExtensionStateEvent { ADDED, REMOVED, TOGGLED }

fun interface ExtensionStateListener {
    fun onExtensionStateChanged(event: ExtensionStateEvent)
}
