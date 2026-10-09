package wtf.mazy.peel.ui.webapplist

import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.entitylist.IdleAction

/**
 * One search position on the list screen — the contained view that covers the screen, or the
 * bar on the bottom line. A surface owns its own views, its query field and the keyboard, and
 * reports when the session starts and ends and what is typed; the results shown in it are
 * [SearchModeController]'s. It also states the facts that make its position a position: which
 * floating action belongs to it and what it does while idle, which action the app bar offers
 * instead, and how a list clears it — the two positions are mirror images, each putting one of
 * search and add within reach and the other in the app bar — so a screen switches positions
 * by switching surfaces and nothing else.
 */
interface SearchSurface {

    val isActive: Boolean

    val query: String

    val resultsList: RecyclerView

    val emptyState: ListEmptyState

    val suggestions: UrlSuggestionStrip

    val fab: FloatingActionButton

    val idleAction: IdleAction

    val appBarAction: AppBarAction

    /** How far a list pads its foot so its last row scrolls clear of what this position floats over it. */
    val listBottomClearance: Int

    var onActiveChanged: (Boolean) -> Unit

    var onQueryChanged: (String) -> Unit

    fun enter()

    fun exit()

    fun setQuery(query: String)

    /**
     * Called once the view hierarchy has restored its state. [wasActive] is whether search was
     * open when the state was saved; whether the surface comes back open is its own affair.
     */
    fun onRestoredState(wasActive: Boolean)

    /** This position is now the one on screen. */
    fun onAttached() = Unit

    /** This position has left the screen; its session has already been ended. */
    fun onDetached() = Unit
}

enum class AppBarAction { SEARCH, ADD }
