package wtf.mazy.peel.ui.entitylist.binders

import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.IconOwnerBinder

object WebAppBinder : IconOwnerBinder<WebApp>() {
    override fun contentEquals(a: WebApp, b: WebApp): Boolean =
        a.contentFingerprint == b.contentFingerprint
}
