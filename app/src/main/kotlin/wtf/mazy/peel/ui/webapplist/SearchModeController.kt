package wtf.mazy.peel.ui.webapplist

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.ListChrome

/**
 * Drives search on the list screen through whichever [SearchSurface] the search position
 * setting has chosen, and performs the switch between surfaces: the one leaving the screen has
 * its session ended and its controls taken back, the one arriving hands its floating action and
 * bar to the chrome. The surface owns its views, its query and the keyboard; this owns the one
 * results adapter and moves it between surfaces, so the two positions never hold results at the
 * same time.
 */
class SearchModeController(
    private val activity: AppCompatActivity,
    private val selection: EntitySelectionController<WebApp>,
    private val chrome: ListChrome<WebApp>,
    initial: SearchSurface,
    private val onChanged: () -> Unit,
    private val onSurfaceChanged: () -> Unit,
) {

    val isActive: Boolean get() = surface.isActive

    val offersSearchAction: Boolean get() = surface.offersSearchAction

    val listBottomClearance: Int get() = surface.listBottomClearance

    private var surface: SearchSurface = initial

    private val searchAdapter = WebAppListAdapter(activity, selection).apply {
        groupFilter = null
        showGroupLabels = true
    }

    private val scrollToTopObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scrollToTop()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scrollToTop()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scrollToTop()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
            scrollToTop()
    }

    init {
        searchAdapter.registerAdapterDataObserver(scrollToTopObserver)
        bind(initial)
    }

    /**
     * Hands search to [next]. Any open session and any selection are ended first, so the screen
     * never changes position with a mode open.
     */
    fun attach(next: SearchSurface) {
        val previous = surface
        if (previous === next) return
        previous.exit()
        selection.exit()
        unbind(previous)
        surface = next
        bind(next)
        onSurfaceChanged()
    }

    fun enter() = surface.enter()

    fun exit() = surface.exit()

    fun onRestoredState(wasActive: Boolean) = surface.onRestoredState(wasActive)

    fun onDataChanged() {
        if (isActive) refreshResults()
    }

    /** A selection made from the results takes the screen where the position cannot share it. */
    fun onSelectionChanged() {
        if (selection.isActive && !surface.coexistsWithSelection) exit()
    }

    private fun bind(surface: SearchSurface) {
        surface.resultsList.layoutManager = LinearLayoutManager(activity)
        surface.resultsList.adapter = searchAdapter
        surface.onQueryChanged = { query ->
            // A surface discards its query as it closes; refreshing then would repopulate the
            // results with every app while they are still fading out.
            if (surface.isActive) {
                searchAdapter.searchQuery = query
                refreshResults()
            }
        }
        surface.onActiveChanged = { active ->
            if (active) {
                searchAdapter.searchQuery = surface.query
                refreshResults()
            }
            onChanged()
        }
        chrome.setControls(fab = surface.fab, searchBar = surface.searchBar)
        surface.onAttached()
    }

    private fun unbind(surface: SearchSurface) {
        surface.onDetached()
        surface.onActiveChanged = {}
        surface.onQueryChanged = {}
        surface.resultsList.adapter = null
        surface.resultsList.layoutManager = null
    }

    private fun refreshResults() {
        val empty = searchAdapter.updateWebAppList()
        surface.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        surface.resultsList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun scrollToTop() = surface.resultsList.scrollToPosition(0)
}
