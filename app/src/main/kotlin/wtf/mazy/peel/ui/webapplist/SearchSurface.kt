package wtf.mazy.peel.ui.webapplist

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * One search position on the list screen — the contained view that covers the screen, or the
 * bar on the bottom line. A surface owns its own views, its query field and the keyboard, and
 * reports when the session starts and ends and what is typed; the results shown in it are
 * [SearchModeController]'s. It also states the facts that make its position a position: which
 * floating action and bar belong to it, how a list clears it, and how it relates to the app bar
 * and to selection — so a screen switches positions by switching surfaces and nothing else.
 */
interface SearchSurface {

    val isActive: Boolean

    val query: String

    val resultsList: RecyclerView

    val emptyState: TextView

    /** The floating action that belongs to this position. */
    val fab: FloatingActionButton

    /** The search bar that shares the floating action's line, if this position has one. */
    val searchBar: View?

    /** Whether the app bar offers a search action; a position with a bar of its own does not. */
    val offersSearchAction: Boolean

    /** Whether a search session can stay open while items are selected. */
    val coexistsWithSelection: Boolean

    /** How far a list pads its foot so its last row scrolls clear of what this position floats over it. */
    val listBottomClearance: Int

    var onActiveChanged: (Boolean) -> Unit

    var onQueryChanged: (String) -> Unit

    fun enter()

    fun exit()

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
