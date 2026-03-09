package wtf.mazy.peel.ui.webapplist

interface SelectionModeHost {
    val isInSelectionMode: Boolean
    fun isSelected(uuid: String): Boolean
    fun enterSelectionMode(uuid: String)
    fun toggleSelection(uuid: String)
}
