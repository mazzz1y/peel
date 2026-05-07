package wtf.mazy.peel.ui.entitylist

import wtf.mazy.peel.model.IconOwner
import wtf.mazy.peel.model.StableIdRegistry

interface EntityBinder<T : Any> {
    fun uuid(item: T): String
    fun stableId(item: T): Long = StableIdRegistry.idFor(uuid(item))
    fun bindIcon(host: EntityRowView, item: T, selected: Boolean, checkIconColor: Int)
    fun animateIconSwap(host: EntityRowView, item: T, selected: Boolean, checkIconColor: Int) {
        bindIcon(host, item, selected, checkIconColor)
    }

    fun contentEquals(a: T, b: T): Boolean
}

abstract class IconOwnerBinder<T : IconOwner> : EntityBinder<T> {
    override fun uuid(item: T): String = item.uuid
    override fun stableId(item: T): Long = item.stableId

    override fun bindIcon(host: EntityRowView, item: T, selected: Boolean, checkIconColor: Int) {
        EntityRowAnimator.applyIconState(host, item, selected, checkIconColor)
    }

    override fun animateIconSwap(
        host: EntityRowView,
        item: T,
        selected: Boolean,
        checkIconColor: Int,
    ) {
        EntityRowAnimator.animateIconSwap(host, item, selected, checkIconColor)
    }
}
