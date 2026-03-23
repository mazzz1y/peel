package wtf.mazy.peel.ui.toolbar

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import wtf.mazy.peel.model.WebApp

interface ToolbarModeHost {
    val hostActivity: AppCompatActivity
    val toolbar: MaterialToolbar
    val fab: FloatingActionButton
    val tabLayout: TabLayout?
    val viewPager: ViewPager2?

    fun crossfadeToolbar(swap: () -> Unit)
    fun animateFabSwap(iconRes: Int)
    fun applyNormalToolbar()
    fun refreshCurrentPages()
    fun shareApps(webApps: List<WebApp>, includeSecrets: Boolean)
    fun updateBackPressEnabled()

    fun addPendingDeletes(uuids: Collection<String>)
    fun clearPendingDeletes(uuids: Collection<String>)
    fun dispatchSelectionEntered(toggledUuid: String)
    fun dispatchSelectionToggled(uuid: String)
    fun dispatchSelectionExited(previouslySelected: Set<String>)
    fun onSearchModeExited()

    fun removeSearchViewFromToolbar() {
        val tb = toolbar
        for (i in tb.childCount - 1 downTo 0) {
            if (tb.getChildAt(i) is SearchView) tb.removeViewAt(i)
        }
    }
}
