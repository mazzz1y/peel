package wtf.mazy.peel.ui.entitylist

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton

interface EntityListHost {
    val hostActivity: AppCompatActivity
    val toolbar: MaterialToolbar
    val fab: FloatingActionButton

    fun crossfadeToolbar(swap: () -> Unit)
    fun animateFabSwap(iconRes: Int)
    fun applyNormalToolbar()
    fun updateBackPressEnabled()

    fun removeSearchViewFromToolbar() {
        for (i in toolbar.childCount - 1 downTo 0) {
            if (toolbar.getChildAt(i) is SearchView) toolbar.removeViewAt(i)
        }
    }
}
