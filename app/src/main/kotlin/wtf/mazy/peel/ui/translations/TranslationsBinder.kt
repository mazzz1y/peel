package wtf.mazy.peel.ui.translations

import wtf.mazy.peel.ui.entitylist.EntityBinder
import wtf.mazy.peel.ui.entitylist.EntityRowView

class TranslationsBinder : EntityBinder<TranslationItem> {

    override fun uuid(item: TranslationItem): String = item.code

    override fun bindIcon(
        host: EntityRowView,
        item: TranslationItem,
        selected: Boolean,
        checkIconColor: Int,
    ) {
    }

    override fun contentEquals(a: TranslationItem, b: TranslationItem): Boolean =
        a.code == b.code &&
                a.displayName == b.displayName &&
                a.isDownloaded == b.isDownloaded
}
