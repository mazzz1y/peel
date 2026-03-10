package wtf.mazy.peel.ui.toolbar

import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.ui.webapplist.WebAppListAdapter

class SearchModeController(
    private val host: ToolbarModeHost,
    private val exitOtherMode: () -> Unit,
) {

    val isActive: Boolean get() = _searchMode

    var searchResultsList: RecyclerView? = null
    var searchEmptyState: TextView? = null

    private var _searchMode = false
    private var searchAdapter: WebAppListAdapter? = null

    fun enter() {
        if (_searchMode) return
        exitOtherMode()
        _searchMode = true
        host.updateBackPressEnabled()

        val activity = host.hostActivity
        searchAdapter = WebAppListAdapter(activity).apply { groupFilter = null }
        searchAdapter?.registerAdapterDataObserver(scrollToTopObserver)
        searchResultsList?.layoutManager = LinearLayoutManager(activity)
        searchResultsList?.adapter = searchAdapter
        searchAdapter?.updateWebAppList()

        host.tabLayout?.visibility = View.GONE
        host.viewPager?.visibility = View.GONE
        searchResultsList?.visibility = View.VISIBLE
        updateEmptyState()
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

        searchResultsList?.visibility = View.GONE
        searchEmptyState?.visibility = View.GONE
        host.viewPager?.visibility = View.VISIBLE
        if (DataManager.instance.sortedGroups.isNotEmpty()) {
            host.tabLayout?.visibility = View.VISIBLE
        }

        searchAdapter = null
        searchResultsList?.adapter = null

        host.applyNormalToolbar()
        host.fab.show()
        host.refreshCurrentPages()
    }

    fun onDataChanged() {
        searchAdapter?.updateWebAppList()
        updateEmptyState()
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
