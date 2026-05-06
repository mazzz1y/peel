package wtf.mazy.peel.ui.webapplist

import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntityListHost
import wtf.mazy.peel.ui.entitylist.EntitySelectionController

interface SearchableHost : EntityListHost {
    val tabLayout: TabLayout
    val viewPager: ViewPager2
    val selectionController: EntitySelectionController<WebApp>
    fun onSearchModeExited()
}
