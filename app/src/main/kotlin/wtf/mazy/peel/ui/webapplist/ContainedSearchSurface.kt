package wtf.mazy.peel.ui.webapplist

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.search.SearchView
import kotlin.math.abs
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.animateReflow
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.applyBottomScreenInsets

/**
 * The Top search position: Material's contained [SearchView], a full-screen surface faded in
 * over the list with the query pill at the top. It survives recreation, since the view restores
 * its own text and visibility.
 */
class ContainedSearchSurface(
    activity: AppCompatActivity,
    private val searchView: SearchView,
    private val content: ViewGroup,
    override val fab: FloatingActionButton,
    override val resultsList: RecyclerView,
    override val emptyState: ListEmptyState,
    override val suggestions: UrlSuggestionStrip,
) : SearchSurface {

    override var isActive: Boolean = false
        private set

    override val query: String get() = searchView.text.toString()

    override val offersSearchAction: Boolean = true

    override val listBottomClearance: Int =
        activity.resources.getDimensionPixelSize(R.dimen.list_bottom_line_clearance)

    override var onActiveChanged: (Boolean) -> Unit = {}
    override var onQueryChanged: (String) -> Unit = {}

    private var fadeAnimator: ValueAnimator? = null
    private var pendingFadeIn: OneShotPreDrawListener? = null
    private var keyboardRequested = false
    private var animationsHeld = false
    private val resultsItemAnimator = resultsList.itemAnimator
    private val contentReflow: LayoutTransition
    private val insetsController = WindowCompat.getInsetsController(activity.window, searchView)
    private val showKeyboard = Runnable {
        insetsController.show(WindowInsetsCompat.Type.ime())
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(searchView) { _, insets -> insets }
        content.animateReflow()
        contentReflow = content.layoutTransition
        resultsList.applyBottomScreenInsets()
        searchView.editText.doAfterTextChanged { text -> onQueryChanged(text?.toString().orEmpty()) }
        searchView.toolbar.setNavigationOnClickListener { exit() }
    }

    override fun enter() {
        if (isActive) return
        isActive = true
        searchView.clearText()
        keyboardRequested = true
        showSurface()
        onActiveChanged(true)
    }

    override fun exit() {
        if (!isActive) return
        isActive = false
        keyboardRequested = false
        searchView.editText.removeCallbacks(showKeyboard)
        searchView.editText.clearFocus()
        insetsController.hide(WindowInsetsCompat.Type.ime())
        hideSurface()
        onActiveChanged(false)
    }

    override fun setQuery(query: String) {
        searchView.setText(query)
        searchView.editText.setSelection(query.length)
    }

    override fun onRestoredState(wasActive: Boolean) {
        if (!wasActive || isActive) return
        isActive = true
        searchView.setVisible(true)
        onActiveChanged(true)
    }

    // The surface is inflated GONE; its first measure and layout happen on the frame after
    // setVisible(true), so the fade is started on that frame's pre-draw rather than now, and
    // changes made while hidden would otherwise animate over that first frame.
    private fun showSurface() {
        cancelPendingFadeIn()
        if (fadeAnimator?.isRunning == true) {
            fadeTo(1f)
            requestKeyboardIfPending()
            return
        }
        searchView.alpha = 0f
        holdAnimations(true)
        searchView.setVisible(true)
        pendingFadeIn = OneShotPreDrawListener.add(searchView) {
            pendingFadeIn = null
            holdAnimations(false)
            fadeTo(1f)
            requestKeyboardIfPending()
        }
    }

    private fun hideSurface() {
        cancelPendingFadeIn()
        holdAnimations(false)
        fadeTo(0f) { searchView.setVisible(false) }
    }

    // Reinstalling an animator or a transition cancels whatever it is running.
    private fun holdAnimations(held: Boolean) {
        if (held == animationsHeld) return
        animationsHeld = held
        resultsList.itemAnimator = if (held) null else resultsItemAnimator
        content.layoutTransition = if (held) null else contentReflow
        suggestions.animated = !held
        emptyState.animated = !held
    }

    // SearchView.requestFocusAndShowKeyboard() posts an uncancellable delayed show that would
    // raise the keyboard after an exit; this request is owned here and removed on exit.
    private fun requestKeyboardIfPending() {
        if (!keyboardRequested) return
        keyboardRequested = false
        searchView.editText.requestFocus()
        searchView.editText.post(showKeyboard)
    }

    // Cancelling an animator still dispatches onAnimationEnd, so the end action is only run
    // when the fade reached its target.
    private fun fadeTo(target: Float, onReached: () -> Unit = {}) {
        fadeAnimator?.cancel()
        val start = searchView.alpha
        fadeAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (Const.ANIM_DURATION_MEDIUM * abs(target - start)).toLong()
            addUpdateListener { searchView.alpha = it.animatedValue as Float }
            var cancelled = false
            doOnCancel { cancelled = true }
            doOnEnd { if (!cancelled) onReached() }
            start()
        }
    }

    private fun cancelPendingFadeIn() {
        pendingFadeIn?.removeListener()
        pendingFadeIn = null
    }
}
