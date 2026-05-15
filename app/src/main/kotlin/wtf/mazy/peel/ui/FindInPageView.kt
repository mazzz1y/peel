package wtf.mazy.peel.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import org.mozilla.geckoview.GeckoSession
import wtf.mazy.peel.R

class FindInPageView(
    private val parent: FrameLayout,
    private val session: GeckoSession,
    private val onClose: () -> Unit,
) {
    private val density = parent.resources.displayMetrics.density
    private val root: View =
        LayoutInflater.from(parent.context).inflate(R.layout.view_find_in_page, parent, false)
    private val query: EditText = root.findViewById(R.id.findInPageQuery)
    private val counter: TextView = root.findViewById(R.id.findInPageCounter)
    private val prevButton: ImageButton = root.findViewById(R.id.findInPagePrev)
    private val nextButton: ImageButton = root.findViewById(R.id.findInPageNext)
    private val closeButton: ImageButton = root.findViewById(R.id.findInPageClose)

    private val debounceRunnable = Runnable { runFind(GeckoSession.FINDER_FIND_FORWARD) }
    private val slideInterpolator = FastOutSlowInInterpolator()
    private var dismissing = false

    init {
        session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        counter.text = EMPTY_COUNTER

        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            val margin = (MARGIN_DP * density).toInt()
            setMargins(margin, margin, margin, margin)
            gravity = android.view.Gravity.BOTTOM
        }
        applyInsets()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> applyInsets(insets); insets }

        root.alpha = 0f
        parent.addView(root)
        root.doOnLayout {
            root.translationY = it.height.toFloat()
            animateSlide(toY = 0f, toAlpha = 1f)
        }

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                root.removeCallbacks(debounceRunnable)
                if (s.isNullOrEmpty()) {
                    session.finder.clear()
                    counter.text = EMPTY_COUNTER
                } else {
                    root.postDelayed(debounceRunnable, DEBOUNCE_MS)
                }
            }
        })
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runFind(GeckoSession.FINDER_FIND_FORWARD); true
            } else false
        }
        prevButton.setOnClickListener { runFind(GeckoSession.FINDER_FIND_BACKWARDS) }
        nextButton.setOnClickListener { runFind(GeckoSession.FINDER_FIND_FORWARD) }
        closeButton.setOnClickListener { remove() }

        query.requestFocus()
        query.post { showKeyboard() }
    }

    fun remove() {
        if (dismissing) return
        dismissing = true
        root.removeCallbacks(debounceRunnable)
        hideKeyboard()
        session.finder.clear()
        animateSlide(toY = root.height.toFloat(), toAlpha = 0f) { detachAndNotify() }
    }

    private fun animateSlide(toY: Float, toAlpha: Float, endAction: Runnable? = null) {
        root.animate()
            .translationY(toY)
            .alpha(toAlpha)
            .setDuration(ANIM_DURATION_MS)
            .setInterpolator(slideInterpolator)
            .withEndAction(endAction)
            .start()
    }

    private fun detachAndNotify() {
        if (root.parent != null) parent.removeView(root)
        onClose()
    }

    private fun applyInsets(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(parent)) {
        val sysBottom = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        root.updateLayoutParams<FrameLayout.LayoutParams> {
            val baseMargin = (MARGIN_DP * density).toInt()
            bottomMargin = baseMargin + maxOf(sysBottom, imeBottom)
        }
    }

    private fun runFind(flags: Int) {
        val text = query.text?.toString().orEmpty()
        if (text.isEmpty()) {
            session.finder.clear()
            counter.text = EMPTY_COUNTER
            return
        }
        session.finder.find(text, flags).then<Void> { result ->
            counter.text = if (result == null || result.total <= 0) {
                NO_MATCH_COUNTER
            } else {
                parent.context.getString(R.string.find_in_page_counter, result.current, result.total)
            }
            null
        }
    }

    private fun showKeyboard() {
        val imm = parent.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = parent.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    private companion object {
        const val DEBOUNCE_MS = 120L
        const val MARGIN_DP = 8
        const val ANIM_DURATION_MS = 200L
        const val EMPTY_COUNTER = ""
        const val NO_MATCH_COUNTER = "0 / 0"
    }
}
