package wtf.mazy.peel.activities

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import wtf.mazy.peel.databinding.ActivityToolbarBaseBinding
import wtf.mazy.peel.model.DataLoad
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.ProtectionGate
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

    // A screen restored after the process was stopped comes up before the data is open, and
    // would otherwise show and save the defaults over the stored settings.
    protected fun whenDataReady(build: () -> Unit) {
        if (DataManager.load.value == DataLoad.Ready) {
            dataReady = true
            build()
            return
        }
        baseBinding.root.visibility = View.INVISIBLE
        lifecycleScope.launch {
            DataManager.load.first { it == DataLoad.Ready }
            dataReady = true
            baseBinding.root.visibility = View.VISIBLE
            build()
        }
    }

    protected var dataReady = false
        private set

    private var unlockNeeded = false
    private var unlockedAlready = false
    private var asking = false
    private var pickerOpen = false

    // The screen stops while its own picker (an icon, a certificate) is up; that is not leaving
    // it, so the unlock holds.
    protected fun pickerOpening() {
        pickerOpen = true
    }

    // Like a protected web app's lock, the pass ends when the screen is left, which turning the
    // screen off also does.
    protected fun requireUnlock(needed: Boolean, unlocked: Boolean = false) {
        unlockNeeded = needed
        unlockedAlready = unlocked
        if (needed) hideFromRecents()
        askIfLocked()
    }

    private fun askIfLocked() {
        val gate: UnlockPass by viewModels()
        if (gate.passed || asking) return
        asking = true
        baseBinding.root.visibility = View.INVISIBLE
        val deny = {
            asking = false
            finish()
        }
        ProtectionGate.require(this, unlockNeeded, unlockedAlready, onDenied = deny) {
            asking = false
            gate.passed = true
            baseBinding.root.visibility = View.VISIBLE
        }
    }

    // Recents' own switch only exists from 13; the secure flag also keeps screenshots out.
    private fun hideFromRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        pickerOpen = false
    }

    override fun onStart() {
        super.onStart()
        if (unlockNeeded && !isChangingConfigurations && !pickerOpen) askIfLocked()
    }

    override fun onStop() {
        super.onStop()
        if (!unlockNeeded || isChangingConfigurations || asking || pickerOpen) return
        val gate: UnlockPass by viewModels()
        gate.passed = false
        unlockedAlready = false
        baseBinding.root.visibility = View.INVISIBLE
    }

    class UnlockPass : ViewModel() {
        var passed = false
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
