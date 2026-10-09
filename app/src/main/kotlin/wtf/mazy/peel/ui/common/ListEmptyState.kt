package wtf.mazy.peel.ui.common

import android.view.View

/**
 * The text that stands in for a list with nothing in it, faded at the pace of the row
 * animations around it. The list itself is never hidden — an empty one draws nothing — so its
 * last rows are free to finish their exit.
 */
class ListEmptyState(private val text: View) {

    var animated = true

    private var rendered = false

    fun render(empty: Boolean) {
        text.fadeVisibility(visible = empty, animated = animated && rendered)
        rendered = true
    }
}
