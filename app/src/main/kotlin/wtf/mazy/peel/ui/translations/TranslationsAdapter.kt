package wtf.mazy.peel.ui.translations

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityListViewHolder
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowActions

class TranslationsAdapter(
    @ColorInt checkIconColor: Int,
    onDownload: (TranslationItem) -> Unit,
    onTrash: (TranslationItem) -> Unit,
) : EntityListAdapter<TranslationItem, TranslationsAdapter.ViewHolder>(
    binder = TranslationsBinder(),
    actions = TranslationRowActions(onDownload, onTrash),
    checkIconColor = checkIconColor,
) {

    class ViewHolder(itemView: View) : EntityListViewHolder(itemView) {
        override val indicators: List<ImageView> = emptyList()
        val name: TextView = itemView.findViewById(R.id.item_primary)
    }

    override fun layoutRes(): Int = R.layout.item_translation
    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun bindRow(holder: ViewHolder, row: EntityRow<TranslationItem>) {
        holder.name.text = row.entity.displayName
    }

    private class TranslationRowActions(
        private val onDownload: (TranslationItem) -> Unit,
        private val onTrash: (TranslationItem) -> Unit,
    ) : EntityRowActions<TranslationItem> {
        override fun onItemClick(item: TranslationItem) {}
        override fun onItemIconClick(item: TranslationItem) {}
        override fun onItemMenu(view: View, item: TranslationItem) {
            if (item.isDownloaded) onTrash(item) else onDownload(item)
        }
    }
}
