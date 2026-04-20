package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import wtf.mazy.peel.databinding.ActivityToolbarBaseBinding

abstract class ToolbarBaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private lateinit var _binding: VB
    protected val binding
        get() = _binding

    private lateinit var baseBinding: ActivityToolbarBaseBinding

    abstract fun inflateBinding(layoutInflater: LayoutInflater): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        baseBinding = ActivityToolbarBaseBinding.inflate(layoutInflater)
        setContentView(baseBinding.root)

        _binding = inflateBinding(layoutInflater)
        baseBinding.activityContent.addView(_binding.root)

        val toolbar = baseBinding.toolbar.topAppBar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this) { finish() }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
    }

    protected fun setupKeyboardPadding(scrollView: NestedScrollView) {
        setupKeyboardPadding(scrollView as ViewGroup) { container, bottom ->
            val content = container.getChildAt(0) ?: return@setupKeyboardPadding
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
        }
    }

    protected fun setupKeyboardPadding(recyclerView: RecyclerView) {
        val baseBottom = recyclerView.paddingBottom
        setupKeyboardPadding(recyclerView as ViewGroup) { _, bottom ->
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                baseBottom + bottom,
            )
        }
    }

    private fun setupKeyboardPadding(
        scrollContainer: ViewGroup,
        applyBottomPadding: (ViewGroup, Int) -> Unit,
    ) {
        var keyboardHeight = 0
        val contentView = baseBinding.activityContent

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            keyboardHeight = (imeBottom - navBottom).coerceAtLeast(0)

            applyBottomPadding(scrollContainer, keyboardHeight)

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
