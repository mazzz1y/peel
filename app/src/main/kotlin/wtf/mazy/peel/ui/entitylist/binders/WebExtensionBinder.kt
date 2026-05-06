package wtf.mazy.peel.ui.entitylist.binders

import android.content.Context
import org.mozilla.geckoview.WebExtension
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.ui.entitylist.EntityBinder
import wtf.mazy.peel.ui.entitylist.EntityRowView
import wtf.mazy.peel.ui.extensions.ExtensionIconCache

class WebExtensionBinder(context: Context) : EntityBinder<WebExtension> {
    private val appContext = context.applicationContext

    override fun uuid(item: WebExtension): String = item.id
    override fun stableId(item: WebExtension): Long = StableIdRegistry.idFor(item.id)

    override fun bindIcon(
        host: EntityRowView,
        item: WebExtension,
        selected: Boolean,
        checkIconColor: Int,
    ) {
        ExtensionIconCache.bind(host.itemIcon, appContext, item.id, item.metaData.name ?: item.id)
    }

    override fun contentEquals(a: WebExtension, b: WebExtension): Boolean {
        val am = a.metaData
        val bm = b.metaData
        return am.name == bm.name && am.version == bm.version &&
                am.optionsPageUrl == bm.optionsPageUrl
    }
}
