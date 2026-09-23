package wtf.mazy.peel.ui.webapplist

import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import wtf.mazy.peel.ui.entitylist.EntityListHost

interface SearchableHost : EntityListHost, WebAppListHost {
    val tabLayout: TabLayout
    val viewPager: ViewPager2
    fun onSearchModeExited()
}
