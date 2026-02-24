package wtf.mazy.peel.activities

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import wtf.mazy.peel.databinding.ActivityToolbarBaseBinding

abstract class ToolbarBaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private lateinit var _binding: VB
    protected val binding
        get() = _binding

    private var onNavigationClickListener: (() -> Unit)? = null

    abstract fun inflateBinding(layoutInflater: LayoutInflater): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val baseBinding = ActivityToolbarBaseBinding.inflate(layoutInflater)
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

    protected fun setupKeyboardPadding(contentContainer: View) {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = screenHeight - rect.bottom
            if (keyboardHeight > screenHeight * 0.15) {
                contentContainer.setPadding(0, 0, 0, keyboardHeight)
            } else {
                contentContainer.setPadding(0, 0, 0, 0)
            }
        }
    }
}
