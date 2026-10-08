package wtf.mazy.peel.ui.webapplist

import android.content.Intent
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntitySelectionController

interface WebAppListHost {
    val selectionController: EntitySelectionController<WebApp>

    /** How far the list pads its foot so the last row scrolls clear of what floats over it. */
    val listBottomClearance: Int

    fun launchSettings(intent: Intent)
    fun registerFragment(groupFilter: String?, fragment: WebAppListFragment)
    fun unregisterFragment(groupFilter: String?)
}
