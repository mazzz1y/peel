package wtf.mazy.peel.ui.toolbar

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.webapplist.WebAppListAdapter
import wtf.mazy.peel.util.Const

class SearchModeController(
    private val host: ToolbarModeHost,
) {

    val isActive: Boolean get() = _searchMode

    var searchResultsList: RecyclerView? = null
    var searchEmptyState: TextView? = null

    private var _searchMode = false
    private var searchAdapter: WebAppListAdapter? = null

    fun enter() {
        if (_searchMode) return
        _searchMode = true
        host.updateBackPressEnabled()

        val activity = host.hostActivity
        searchAdapter = WebAppListAdapter(activity).apply {
            groupFilter = null
            showGroupLabels = true
        }
        searchAdapter?.registerAdapterDataObserver(scrollToTopObserver)
        searchResultsList?.layoutManager = LinearLayoutManager(activity)
        searchResultsList?.adapter = searchAdapter
        searchAdapter?.updateWebAppList()

        host.tabLayout?.animate()?.alpha(0f)?.setDuration(Const.ANIM_DURATION_FAST)?.withEndAction {
            host.tabLayout?.visibility = View.GONE
        }?.start()
        val hasItems = (searchAdapter?.itemCount ?: 0) > 0
        val visibleView = if (hasItems) searchResultsList else searchEmptyState
        fadeOutThenIn(host.viewPager, {
            host.viewPager?.visibility = View.GONE
            updateEmptyState()
        }, visibleView)

        host.fab.hide()
        applySearchToolbar()
    }

    fun exit() {
        if (!_searchMode) return
        _searchMode = false
        host.updateBackPressEnabled()

        val activity = host.hostActivity
        activity.currentFocus?.let { focused ->
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
        }

        host.onSearchModeExited()

        val showTabs = DataManager.instance.sortedGroups.isNotEmpty()
        fadeOutThenIn(searchResultsList, {
            searchResultsList?.visibility = View.GONE
            searchEmptyState?.visibility = View.GONE
            searchAdapter = null
            searchResultsList?.adapter = null
        }, host.viewPager, if (showTabs) host.tabLayout else null)
    }

    private fun fadeOutThenIn(
        outView: View?,
        onHidden: () -> Unit,
        vararg inViews: View?,
    ) {
        outView?.animate()?.alpha(0f)?.setDuration(Const.ANIM_DURATION_FAST)?.withEndAction {
            onHidden()
            inViews.forEach { view ->
                view ?: return@forEach
                view.alpha = 0f
                view.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(Const.ANIM_DURATION_FAST).start()
            }
        }?.start()
    }

    fun onDataChanged() {
        searchAdapter?.updateWebAppList()
        updateEmptyState()
    }

    fun notifySelectionEntered(toggledUuid: String) {
        val adapter = searchAdapter ?: return
        val toggledPosition = adapter.items.indexOfFirst { it.uuid == toggledUuid }
        for (i in 0 until adapter.items.size) {
            val payload = if (i == toggledPosition)
                WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE
            else
                WebAppListAdapter.PAYLOAD_MODE_CHANGE
            adapter.notifyItemChanged(i, payload)
        }
    }

    fun notifySelectionToggled(uuid: String) {
        val adapter = searchAdapter ?: return
        val position = adapter.items.indexOfFirst { it.uuid == uuid }
        if (position >= 0) {
            adapter.notifyItemChanged(position, WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE)
        }
    }

    fun notifySelectionExited(previouslySelected: Set<String>) {
        val adapter = searchAdapter ?: return
        for (i in 0 until adapter.items.size) {
            val payload = if (adapter.items[i].uuid in previouslySelected)
                WebAppListAdapter.PAYLOAD_SELECTION_TOGGLE
            else
                WebAppListAdapter.PAYLOAD_MODE_CHANGE
            adapter.notifyItemChanged(i, payload)
        }
    }

    private fun applySearchToolbar() {
        host.crossfadeToolbar {
            host.removeSearchViewFromToolbar()
            host.toolbar.menu.clear()
            host.toolbar.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            host.toolbar.setNavigationOnClickListener { exit() }
            host.toolbar.title = ""

            val activity = host.hostActivity
            val searchView = SearchView(activity).apply {
                queryHint = activity.getString(R.string.search)
                isIconified = false
                maxWidth = Int.MAX_VALUE
                setOnCloseListener { true }
                setOnQueryTextListener(
                    object : SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String?) = false
                        override fun onQueryTextChange(newText: String?): Boolean {
                            searchAdapter?.searchQuery = newText.orEmpty()
                            searchAdapter?.updateWebAppList()
                            updateEmptyState()
                            return true
                        }
                    },
                )
            }
            host.toolbar.addView(
                searchView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            searchView.post {
                searchView.requestFocusFromTouch()
                val imm = activity.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(searchView.findFocus(), InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun updateEmptyState() {
        val hasItems = (searchAdapter?.itemCount ?: 0) > 0
        searchEmptyState?.visibility = if (hasItems) View.GONE else View.VISIBLE
        searchResultsList?.visibility = if (hasItems) View.VISIBLE else View.GONE
    }

    private val scrollToTopObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { searchResultsList?.scrollToPosition(0) }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { searchResultsList?.scrollToPosition(0) }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { searchResultsList?.scrollToPosition(0) }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { searchResultsList?.scrollToPosition(0) }
    }
}
