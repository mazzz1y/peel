package wtf.mazy.peel.ui.webapplist

import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntitySelectionController
import wtf.mazy.peel.ui.entitylist.ListChrome
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.util.TypedUrl
import wtf.mazy.peel.util.servesHost

/**
 * Drives search on the list screen through whichever [SearchSurface] the search position
 * setting has chosen, and performs the switch between surfaces: the one leaving the screen has
 * its session ended and its control taken back, the one arriving hands its floating action to
 * the chrome. The surface owns its views, its query and the keyboard; this owns the one
 * results adapter and moves it between surfaces, so the two positions never hold results at the
 * same time.
 *
 * A URL-shaped query is also something to act on: adding it as a web app (unless one with that
 * host exists) or opening it privately, carried out by the host through [onAddUrl] and
 * [onOpenPrivate].
 */
class SearchModeController(
    private val activity: AppCompatActivity,
    private val selection: EntitySelectionController<WebApp>,
    private val chrome: ListChrome<WebApp>,
    initial: SearchSurface,
    private val onChanged: () -> Unit,
    private val onSurfaceChanged: () -> Unit,
    private val onAddUrl: (url: String, onConfirmed: () -> Unit, onCancelled: () -> Unit) -> Unit,
    private val onOpenPrivate: (String) -> Unit,
) {

    val isActive: Boolean get() = surface.isActive

    val appBarAction: AppBarAction get() = surface.appBarAction

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
            } else {
                surface.suggestions.render(emptySet())
            }
            onChanged()
        }
        surface.suggestions.onSuggestion = ::onSuggestion
        chrome.setControls(surface.fab, surface.idleAction)
        surface.onAttached()
    }

    private fun unbind(surface: SearchSurface) {
        surface.onDetached()
        surface.onActiveChanged = {}
        surface.onQueryChanged = {}
        surface.suggestions.onSuggestion = {}
        surface.suggestions.render(emptySet())
        surface.resultsList.adapter = null
        surface.resultsList.layoutManager = null
    }

    // A URL with no matching app is not "nothing found"; the strip stands in for the empty state.
    private fun refreshResults() {
        val empty = searchAdapter.updateWebAppList()
        val offered = suggestionsFor(TypedUrl.parse(searchAdapter.searchQuery))
        surface.suggestions.render(offered)
        surface.emptyState.render(empty && offered.isEmpty())
    }

    private fun suggestionsFor(typed: TypedUrl?): Set<UrlSuggestion> {
        typed ?: return emptySet()
        val pending = PendingDeletes.webApps
        val alreadyAdded = DataManager.webApps.any { it.uuid !in pending && it.servesHost(typed.host) }
        return buildSet {
            if (!alreadyAdded) add(UrlSuggestion.ADD)
            add(UrlSuggestion.OPEN_PRIVATE)
        }
    }

    private fun onSuggestion(suggestion: UrlSuggestion) {
        val query = surface.query
        val typed = TypedUrl.parse(query) ?: return
        when (suggestion) {
            UrlSuggestion.ADD -> onAddUrl(typed.url, ::exit) { resume(query) }
            UrlSuggestion.OPEN_PRIVATE -> {
                exit()
                onOpenPrivate(typed.url)
            }
        }
    }

    // On a surface whose session is the keyboard's, the add dialog's own keyboard has ended it.
    private fun resume(query: String) {
        if (!surface.isActive) surface.enter()
        if (surface.query != query) surface.setQuery(query)
    }

    // A scroll pending over an empty layout makes the layout manager drop the rows outright,
    // without their exit animation.
    private fun scrollToTop() {
        if (searchAdapter.itemCount > 0) surface.resultsList.scrollToPosition(0)
    }
}
