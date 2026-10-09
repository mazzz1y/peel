package wtf.mazy.peel.ui.webapplist

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import wtf.mazy.peel.R
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.applyBottomLineFade
import wtf.mazy.peel.ui.common.fadeVisibility
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.screenInsetBottom
import wtf.mazy.peel.util.setBottomClearance

/**
 * The Bottom search position: the [SearchBar] on the floating bottom line owns the query, and
 * the results are shown in place over the list page under the unchanged app bar. The session
 * is the keyboard's, so it does not survive recreation.
 *
 * The session spans the bar and the results: a D-pad or a screen reader travelling from the
 * field into a result row is still searching, so focus landing in the results keeps the bar's
 * session open rather than ending it on the way.
 */
class BottomSearchSurface(
    activity: AppCompatActivity,
    private val line: View,
    override val fab: FloatingActionButton,
    private val bar: SearchBar,
    private val listPage: View,
    private val results: View,
    override val resultsList: RecyclerView,
    override val emptyState: ListEmptyState,
    override val suggestions: UrlSuggestionStrip,
) : SearchSurface {

    override val isActive: Boolean get() = bar.isActive

    override val query: String get() = bar.query

    override val offersSearchAction: Boolean = false

    override val listBottomClearance: Int =
        activity.resources.getDimensionPixelSize(R.dimen.list_bottom_fade_clearance)

    override var onActiveChanged: (Boolean) -> Unit = {}
    override var onQueryChanged: (String) -> Unit = {}

    private val insetsController = WindowCompat.getInsetsController(activity.window, bar)

    private val lineClearance = activity.resources.getDimensionPixelSize(R.dimen.list_bottom_line_clearance)

    init {
        line.applyBottomLineFade()
        resultsList.applyBottomScreenInsets()
        suggestions.applyBottomLineFade()
        suggestions.applyBottomScreenInsets()
        bar.sessionExtent = results
        suggestions.onOfferingChanged = { clearListOfSuggestions() }
        suggestions.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) clearListOfSuggestions()
        }
        bar.onQueryChanged = { query -> onQueryChanged(query) }
        // However the bar gains or loses focus — a tap, a D-pad, a screen reader, the back
        // control — the keyboard and the results follow it, so there is one path in and one
        // path out rather than one per input method.
        bar.onActiveChanged = { active ->
            val ime = WindowInsetsCompat.Type.ime()
            setResultsShown(active)
            if (active) insetsController.show(ime) else insetsController.hide(ime)
            onActiveChanged(active)
        }
    }

    override fun enter() = bar.focusQuery()

    override fun exit() = bar.close()

    override fun setQuery(query: String) = bar.setQuery(query)

    // The view hierarchy restores the bar's query and focus by id regardless, which would
    // reopen results over a list that was never searched. State is restored before the bar is
    // attached to the window, so its focus watcher is not yet listening and the results are
    // snapped hidden here directly.
    override fun onRestoredState(wasActive: Boolean) {
        bar.reset()
        setResultsShown(false, animated = false)
    }

    // A surface arriving after the screen was restored into the other position has not had
    // its restored session dropped, so it starts clean here.
    override fun onAttached() {
        bar.reset()
        line.visibility = View.VISIBLE
    }

    override fun onDetached() {
        line.visibility = View.GONE
    }

    // While the strip shows, the list clears its rows and their rest above the line instead of
    // the line itself. The strip's bottom padding includes the screen insets, which the list
    // adds for itself.
    private fun clearListOfSuggestions() {
        val rows = suggestions.height - suggestions.paddingTop - suggestions.paddingBottom
        val clearance =
            if (suggestions.isOffering && rows > 0) {
                val restAboveLine = suggestions.paddingBottom - suggestions.screenInsetBottom
                rows + restAboveLine + (listBottomClearance - lineClearance)
            } else {
                listBottomClearance
            }
        resultsList.setBottomClearance(clearance)
    }

    // The results are opaque and cover the page rather than replace it, so the page is drawn
    // throughout the fade in both directions — otherwise leaving search dissolves the results
    // to a bare background. It is dropped from the hierarchy only once the results have fully
    // covered it, so a screen reader does not walk the covered list as well as the results.
    private fun setResultsShown(shown: Boolean, animated: Boolean = true) {
        listPage.visibility = View.VISIBLE
        results.fadeVisibility(visible = shown, animated = animated) {
            if (shown) listPage.visibility = View.INVISIBLE
        }
    }
}
