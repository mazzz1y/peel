package wtf.mazy.peel.ui.webapplist

import android.content.Intent
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntitySelectionController

interface WebAppListHost {
    val selectionController: EntitySelectionController<WebApp>
    fun launchSettings(intent: Intent)
    fun registerFragment(groupFilter: String?, fragment: WebAppListFragment)
    fun unregisterFragment(groupFilter: String?)
}
