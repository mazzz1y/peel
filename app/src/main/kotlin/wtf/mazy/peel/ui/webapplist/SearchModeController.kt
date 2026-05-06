package wtf.mazy.peel.ui.webapplist

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.util.Const

class SearchModeController(
    private val host: SearchableHost,
    private val searchResultsList: RecyclerView,
    private val searchEmptyState: TextView,
) {

    var isActive: Boolean = false
        private set

    private var searchAdapter: WebAppListAdapter? = null

    fun enter() {
        if (isActive) return
        isActive = true
        host.updateBackPressEnabled()

        val activity = host.hostActivity
        searchAdapter = WebAppListAdapter(activity, host.selectionController).apply {
            groupFilter = null
            showGroupLabels = true
        }
        searchAdapter?.registerAdapterDataObserver(scrollToTopObserver)
        searchResultsList.layoutManager = LinearLayoutManager(activity)
        searchResultsList.adapter = searchAdapter
        searchAdapter?.updateWebAppList()

        host.tabLayout.animate().alpha(0f).setDuration(Const.ANIM_DURATION_FAST).withEndAction {
            host.tabLayout.visibility = View.GONE
        }.start()
        val visibleView =
            if ((searchAdapter?.itemCount ?: 0) > 0) searchResultsList else searchEmptyState
        fadeOutThenIn(host.viewPager, {
            host.viewPager.visibility = View.GONE
            updateEmptyState()
        }, visibleView)

        host.fab.hide()
        applySearchToolbar()
    }

    fun exit() {
        if (!isActive) return
        isActive = false
        host.updateBackPressEnabled()

        val activity = host.hostActivity
        activity.currentFocus?.let { focused ->
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(focused.windowToken, 0)
        }

        host.onSearchModeExited()

        val showTabs = DataManager.instance.sortedGroups.isNotEmpty()
        fadeOutThenIn(searchResultsList, {
            searchResultsList.visibility = View.GONE
            searchEmptyState.visibility = View.GONE
            searchAdapter?.unregisterAdapterDataObserver(scrollToTopObserver)
            searchAdapter = null
            searchResultsList.adapter = null
        }, host.viewPager, if (showTabs) host.tabLayout else null)
    }

    fun onDataChanged() {
        searchAdapter?.updateWebAppList()
        updateEmptyState()
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
        searchEmptyState.visibility = if (hasItems) View.GONE else View.VISIBLE
        searchResultsList.visibility = if (hasItems) View.VISIBLE else View.GONE
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
}
