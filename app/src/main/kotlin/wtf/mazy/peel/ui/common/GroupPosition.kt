package wtf.mazy.peel.ui.common

enum class GroupPosition {
    ONLY, FIRST, MIDDLE, LAST;

    val isTop get() = this == ONLY || this == FIRST
    val isBottom get() = this == ONLY || this == LAST

    companion object {
        fun of(index: Int, count: Int): GroupPosition = when {
            count <= 1 -> ONLY
            index == 0 -> FIRST
            index == count - 1 -> LAST
            else -> MIDDLE
        }
    }
}
