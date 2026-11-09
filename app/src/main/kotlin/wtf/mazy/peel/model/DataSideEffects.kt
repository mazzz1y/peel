package wtf.mazy.peel.model

interface DataSideEffects {
    fun onShortcutsRemoved(uuids: List<String>)
    fun onShortcutOwnerChanged(owner: IconOwner)
    fun onSandboxRemoved(contextId: String)
    fun onWebAppsRemoved(uuids: Set<String>)
}
