package wtf.mazy.peel.ui.entitylist.binders

import wtf.mazy.peel.model.Proxy
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.ui.entitylist.EntityBinder
import wtf.mazy.peel.ui.entitylist.EntityRowView

object ProxyBinder : EntityBinder<Proxy> {
    override fun uuid(item: Proxy): String = item.uuid
    override fun stableId(item: Proxy): Long = StableIdRegistry.idFor(item.uuid)

    override fun bindIcon(
        host: EntityRowView,
        item: Proxy,
        selected: Boolean,
        checkIconColor: Int,
    ) {
    }

    override fun contentEquals(a: Proxy, b: Proxy): Boolean =
        a.contentFingerprint == b.contentFingerprint
}
