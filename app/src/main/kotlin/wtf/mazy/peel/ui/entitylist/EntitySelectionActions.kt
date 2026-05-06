package wtf.mazy.peel.ui.entitylist

data class MoveTarget(val title: String, val groupUuid: String?)

interface EntitySelectionActions<T : Any> {
    val pendingDeleteSet: MutableSet<String>

    fun confirmShare(items: List<T>, onConfirm: (Boolean) -> Unit)
    fun share(items: List<T>, includeSecrets: Boolean)

    fun deleteTitle(): String
    fun deleteMessage(count: Int): String
    fun deletedToast(count: Int): String
    suspend fun commitDelete(uuids: List<String>)

    val moveTargets: List<MoveTarget>
        get() = emptyList()

    suspend fun commitMove(uuids: List<String>, targetGroupUuid: String?) {}
}
