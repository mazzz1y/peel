package wtf.mazy.peel.ui

import android.view.Gravity
import android.widget.PopupMenu
import com.google.android.material.button.MaterialButton

fun MaterialButton.bindDropdown(
    items: List<String>,
    currentIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
) {
    text = items.getOrNull(currentIndex()).orEmpty()
    setOnClickListener {
        val popup = PopupMenu(context, this, Gravity.END)
        items.forEachIndexed { i, label -> popup.menu.add(0, i, i, label) }
        popup.setOnMenuItemClickListener { item ->
            onSelected(item.itemId)
            text = items.getOrNull(currentIndex()).orEmpty()
            true
        }
        popup.show()
    }
}
