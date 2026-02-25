package wtf.mazy.peel.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.viewbinding.ViewBinding
import wtf.mazy.peel.databinding.ActivityToolbarBaseBinding

abstract class ToolbarBaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private lateinit var _binding: VB
    protected val binding
        get() = _binding

    private lateinit var baseBinding: ActivityToolbarBaseBinding
    private var onNavigationClickListener: (() -> Unit)? = null

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

        toolbar.setNavigationOnClickListener {
            onNavigationClickListener?.invoke() ?: onBackPressedDispatcher.onBackPressed()
        }
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
    }

    protected fun setupKeyboardPadding(scrollView: NestedScrollView) {
        var keyboardHeight = 0
        val contentView = baseBinding.activityContent

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            keyboardHeight = (imeBottom - navBottom).coerceAtLeast(0)

            updateContentPadding(scrollView, keyboardHeight)

            if (keyboardHeight > 0) {
                scrollView.post { scrollToFocused(scrollView, keyboardHeight) }
            }

            ViewCompat.onApplyWindowInsets(view, insets)
        }

        scrollView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (keyboardHeight > 0 && newFocus != null && newFocus.isDescendantOf(scrollView)) {
                scrollView.post { scrollToFocused(scrollView, keyboardHeight) }
            }
        }
    }

    private fun updateContentPadding(scrollView: NestedScrollView, bottom: Int) {
        val content = scrollView.getChildAt(0) ?: return
        content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
    }

    private fun scrollToFocused(scrollView: NestedScrollView, keyboardHeight: Int) {
        val focused = currentFocus ?: return
        if (!focused.isDescendantOf(scrollView)) return

        val focusedBottom = focused.screenY() + focused.height
        val visibleBottom = scrollView.screenY() + scrollView.height - keyboardHeight
        val overflow = focusedBottom - visibleBottom

        if (overflow > 0) {
            scrollView.smoothScrollBy(0, overflow + (16 * resources.displayMetrics.density).toInt())
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
