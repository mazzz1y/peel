package wtf.mazy.peel.ui.entitylist

import android.view.View
import android.widget.ImageView

data class EntityRow<T : Any>(
    val entity: T,
    val selected: Boolean,
    val inSelectionMode: Boolean,
    val tertiaryText: String? = null,
)

interface EntityRowView {
    val itemIcon: ImageView
    val menuButton: ImageView
    val indicators: List<ImageView>
}

interface EntityRowActions<T : Any> {
    fun onItemClick(item: T)
    fun onItemIconClick(item: T)
    fun onItemMenu(view: View, item: T)
}

const val PAYLOAD_SELECTION = "selection"
const val PAYLOAD_MODE = "mode"

fun mergedRowPayload(payloads: List<Any>): Set<String> =
    payloads.flatMap { (it as? Set<*>)?.filterIsInstance<String>() ?: emptyList() }.toSet()

fun entityRowChangePayload(
    selectionChanged: Boolean,
    modeChanged: Boolean,
): Set<String>? {
    val tags = mutableSetOf<String>()
    if (selectionChanged) tags.add(PAYLOAD_SELECTION)
    if (modeChanged) tags.add(PAYLOAD_MODE)
    return tags.takeIf { it.isNotEmpty() }
}
