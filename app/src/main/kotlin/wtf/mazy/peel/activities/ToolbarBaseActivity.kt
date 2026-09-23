package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import wtf.mazy.peel.databinding.ActivityToolbarBaseBinding
import wtf.mazy.peel.ui.common.PeelActivity
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.applyToolbarScreenInsets
import wtf.mazy.peel.util.disableSystemBarContrastEnforcement

abstract class ToolbarBaseActivity<VB : ViewBinding> : PeelActivity() {

    private lateinit var _binding: VB
    protected val binding
        get() = _binding

    private lateinit var baseBinding: ActivityToolbarBaseBinding

    abstract fun inflateBinding(layoutInflater: LayoutInflater): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableSystemBarContrastEnforcement()
        super.onCreate(savedInstanceState)

        baseBinding = ActivityToolbarBaseBinding.inflate(layoutInflater)
        setContentView(baseBinding.root)
        applyToolbarScreenInsets()

        _binding = inflateBinding(layoutInflater)
        baseBinding.activityContent.addView(_binding.root)

        val toolbar = baseBinding.toolbar.topAppBar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener { finish() }
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
    }

    // The container pads itself past the navigation bar and keyboard; this only keeps the focused
    // field in view when the keyboard rises.
    protected fun setupScrollInsets(scrollContainer: ViewGroup) {
        scrollContainer.applyBottomScreenInsets()
        var keyboardHeight = 0

        ViewCompat.setOnApplyWindowInsetsListener(baseBinding.activityContent) { view, insets ->
            keyboardHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (keyboardHeight > 0) {
                scrollContainer.post { scrollToFocused(scrollContainer, keyboardHeight) }
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        scrollContainer.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (keyboardHeight > 0 && newFocus != null && newFocus.isDescendantOf(scrollContainer)) {
                scrollContainer.post { scrollToFocused(scrollContainer, keyboardHeight) }
            }
        }
    }

    private fun scrollToFocused(scrollContainer: ViewGroup, keyboardHeight: Int) {
        val focused = currentFocus ?: return
        if (!focused.isDescendantOf(scrollContainer)) return

        val focusedBottom = focused.screenY() + focused.height
        val visibleBottom = scrollContainer.screenY() + scrollContainer.height - keyboardHeight
        val overflow = focusedBottom - visibleBottom

        if (overflow > 0) {
            val dy = overflow + (16 * resources.displayMetrics.density).toInt()
            when (scrollContainer) {
                is NestedScrollView -> scrollContainer.smoothScrollBy(0, dy)
                is RecyclerView -> scrollContainer.smoothScrollBy(0, dy)
            }
        }
    }

    private fun View.screenY(): Int {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location[1]
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }
}
