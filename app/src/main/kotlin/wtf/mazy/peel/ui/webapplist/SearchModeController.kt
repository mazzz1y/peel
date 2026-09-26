package wtf.mazy.peel.ui.webapplist

import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.search.SearchView
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.util.Const
import wtf.mazy.peel.util.applyBottomScreenInsets
import kotlin.math.abs

class SearchModeController(
    activity: AppCompatActivity,
    selection: EntitySelectionController<WebApp>,
    private val searchView: SearchView,
    private val searchResultsList: RecyclerView,
    private val searchEmptyState: TextView,
    private val onChanged: () -> Unit,
) {

    var isActive: Boolean = false
        private set

    private var fadeAnimator: ValueAnimator? = null
    private var pendingFadeIn: OneShotPreDrawListener? = null
    private var keyboardRequested = false
    private val resultsItemAnimator = searchResultsList.itemAnimator
    private val insetsController = WindowCompat.getInsetsController(activity.window, searchView)
    private val showKeyboard = Runnable {
        insetsController.show(WindowInsetsCompat.Type.ime())
    }

    private val scrollToTopObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            searchResultsList.scrollToPosition(0)
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            searchResultsList.scrollToPosition(0)
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            searchResultsList.scrollToPosition(0)
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            searchResultsList.scrollToPosition(0)
        }
    }

    private val searchAdapter = WebAppListAdapter(activity, selection).apply {
        groupFilter = null
        showGroupLabels = true
        registerAdapterDataObserver(scrollToTopObserver)
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(searchView) { _, insets -> insets }
        searchResultsList.applyBottomScreenInsets()
        searchResultsList.layoutManager = LinearLayoutManager(activity)
        searchResultsList.adapter = searchAdapter
        searchView.editText.doAfterTextChanged { text ->
            searchAdapter.searchQuery = text?.toString().orEmpty()
            refreshResults()
        }
        searchView.toolbar.setNavigationOnClickListener { exit() }
    }

    fun enter() {
        if (isActive) return
        isActive = true
        searchView.clearText()
        keyboardRequested = true
        showSurface()
        onChanged()
    }

    fun exit() {
        if (!isActive) return
        isActive = false
        keyboardRequested = false
        searchView.editText.removeCallbacks(showKeyboard)
        searchView.editText.clearFocus()
        insetsController.hide(WindowInsetsCompat.Type.ime())
        hideSurface()
        onChanged()
    }

    fun restore() {
        if (isActive) return
        isActive = true
        searchView.setVisible(true)
        searchAdapter.searchQuery = searchView.text.toString()
        refreshResults()
    }

    fun onDataChanged() = refreshResults()

    // The surface is inflated GONE; its first measure and layout happen on the frame after
    // setVisible(true), so the fade is started on that frame's pre-draw rather than now.
    // Row changes made while the surface was hidden would otherwise play their item
    // animations over that first frame, so the animator is held back until then as well.
    private fun showSurface() {
        cancelPendingFadeIn()
        if (fadeAnimator?.isRunning == true) {
            fadeTo(1f)
            requestKeyboardIfPending()
            return
        }
        searchView.alpha = 0f
        searchResultsList.itemAnimator = null
        searchView.setVisible(true)
        pendingFadeIn = OneShotPreDrawListener.add(searchView) {
            pendingFadeIn = null
            searchResultsList.itemAnimator = resultsItemAnimator
            fadeTo(1f)
            requestKeyboardIfPending()
        }
    }

    private fun hideSurface() {
        cancelPendingFadeIn()
        searchResultsList.itemAnimator = resultsItemAnimator
        fadeTo(0f) { searchView.setVisible(false) }
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

    private fun refreshResults() {
        val empty = searchAdapter.updateWebAppList()
        searchEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        searchResultsList.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
