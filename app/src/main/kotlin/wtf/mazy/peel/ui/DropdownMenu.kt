package wtf.mazy.peel.ui

import android.view.Gravity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton

fun MaterialButton.bindDropdown(
    items: List<String>,
    currentIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
) {
    bindDropdown(
        itemsProvider = { items },
        currentIndex = currentIndex,
        onSelected = onSelected,
    )
}

fun MaterialButton.bindDropdown(
    itemsProvider: () -> List<String>,
    currentIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
) {
    fun refreshText() {
        val items = itemsProvider()
        text = items.getOrNull(currentIndex()).orEmpty()
    }
    refreshText()
    setOnClickListener {
        val items = itemsProvider()
        val popup = PopupMenu(context, this, Gravity.END)
        items.forEachIndexed { i, label -> popup.menu.add(0, i, i, label) }
        popup.setOnMenuItemClickListener { item ->
            onSelected(item.itemId)
            refreshText()
            true
        }
        popup.show()
    }
}
