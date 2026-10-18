package wtf.mazy.peel.ui.controls

object ActionRows {

    fun split(count: Int, perRow: Int): List<Int> {
        if (count <= 0) return emptyList()
        val fit = perRow.coerceAtLeast(1)
        val rows = (count + fit - 1) / fit
        val base = count / rows
        val extra = count % rows
        return List(rows) { row -> if (row < extra) base + 1 else base }
    }
}
