package wtf.mazy.peel.ui

import android.view.Gravity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton

fun MaterialButton.bindDropdown(
    items: List<String>,
    currentIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    buttonItems: List<String> = items,
) {
    bindDropdown(
        optionsProvider = { items.indices.toList() },
        label = { items[it] },
        current = { currentIndex().takeIf { it in items.indices } },
        onSelected = onSelected,
        buttonLabel = { buttonItems[it] },
    )
}

// The picked option comes from the list that was shown, which may since have changed.
fun <T> MaterialButton.bindDropdown(
    optionsProvider: () -> List<T>,
    label: (T) -> String,
    current: () -> T?,
    onSelected: (T) -> Unit,
    buttonLabel: (T) -> String = label,
) {
    fun refreshText() {
        text = current()?.let(buttonLabel).orEmpty()
    }
    refreshText()
    setOnClickListener {
        val shown = optionsProvider()
        val popup = PopupMenu(context, this, Gravity.END)
        shown.forEachIndexed { i, option -> popup.menu.add(0, i, i, label(option)) }
        popup.setOnMenuItemClickListener { item ->
            onSelected(shown[item.itemId])
            refreshText()
            true
        }
        popup.show()
    }
}
