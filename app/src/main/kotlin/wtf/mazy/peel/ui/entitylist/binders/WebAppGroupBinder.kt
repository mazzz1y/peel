package wtf.mazy.peel.ui.entitylist.binders

import wtf.mazy.peel.model.WebAppGroup
import wtf.mazy.peel.ui.entitylist.IconOwnerBinder

object WebAppGroupBinder : IconOwnerBinder<WebAppGroup>() {
    override fun contentEquals(a: WebAppGroup, b: WebAppGroup): Boolean =
        a.contentFingerprint == b.contentFingerprint
}
