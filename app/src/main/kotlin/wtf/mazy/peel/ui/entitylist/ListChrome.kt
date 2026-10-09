package wtf.mazy.peel.ui.entitylist

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import wtf.mazy.peel.R

/**
 * The single writer of a list screen's chrome — the app bar and the floating action — derived
 * from the selection and from whether search is active: selection → back arrow, count,
 * selection menu and share action; searching with nothing selected → no floating action;
 * otherwise the normal bar and the add action. The menu goes through the activity's
 * options-menu callbacks so the action bar's own repopulation never drops it.
 *
 * Which floating action is driven is set with [setControls]: a screen whose search position
 * can change hands over the action of the position it has just laid out.
 */
class ListChrome<T : Any>(
    private val activity: AppCompatActivity,
    private val toolbar: MaterialToolbar,
    fab: FloatingActionButton,
    private val selection: EntitySelectionController<T>,
    private val applyNormalToolbar: (MaterialToolbar) -> Unit,
) {

    private var fab: FloatingActionButton = fab

    private enum class ToolbarMode { NORMAL, SELECTION }

    private var appliedToolbarMode: ToolbarMode? = null
    private var appliedFabIcon: Int? = null

    private val config get() = selection.config

    init {
        toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
    }

    fun render(searching: Boolean = false, animated: Boolean = true) {
        renderToolbar(animated)
        renderFab(searching, animated)
    }

    fun setControls(fab: FloatingActionButton) {
        if (this.fab === fab) return
        this.fab.hide()
        this.fab = fab
        appliedFabIcon = null
    }

    fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!selection.isActive) return false
        config.selectionMenuRes?.let { activity.menuInflater.inflate(it, menu) }
        config.moveActionId?.let { id ->
            menu.findItem(id)?.isVisible = selection.hasMoveTargets
        }
        return true
    }

    fun onOptionsItemSelected(item: MenuItem): Boolean =
        selection.isActive && selection.onMenuItemClicked(item)

    fun onFabClicked(): Boolean {
        if (!selection.isActive) return false
        selection.performShare()
        return true
    }

    fun handleBackPress(): Boolean {
        if (!selection.isActive) return false
        selection.exit()
        return true
    }

    fun saveState(outState: Bundle) {
        outState.putStringArray(STATE_SELECTED_UUIDS, selection.selectedIds.toTypedArray())
    }

    fun restoreState(savedInstanceState: Bundle?, exists: (String) -> Boolean) {
        val saved = savedInstanceState?.getStringArray(STATE_SELECTED_UUIDS) ?: return
        selection.restore(saved.filter(exists))
    }

    private fun renderToolbar(animated: Boolean) {
        val mode = if (selection.isActive) ToolbarMode.SELECTION else ToolbarMode.NORMAL
        if (mode == appliedToolbarMode) {
            if (mode == ToolbarMode.SELECTION) toolbar.title = selectionTitle()
            return
        }
        appliedToolbarMode = mode
        val swap = {
            when (mode) {
                ToolbarMode.SELECTION -> {
                    toolbar.setNavigationIcon(R.drawable.ic_symbols_arrow_back_24)
                    toolbar.title = selectionTitle()
                }

                ToolbarMode.NORMAL -> applyNormalToolbar(toolbar)
            }
            activity.invalidateOptionsMenu()
        }
        if (animated) EntityListAnimations.crossfadeToolbar(toolbar, swap) else swap()
    }

    private fun renderFab(searching: Boolean, animated: Boolean) {
        val selected = selection.isActive
        if (!selected && searching) {
            fab.hide()
            return
        }
        // A contained search surface marks its siblings NO_HIDE_DESCENDANTS while shown; a
        // floating action raised above it has to opt back in.
        fab.importantForAccessibility =
            if (searching) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val icon = if (selected) config.activeFabIcon else config.idleFabIcon
        val description = if (selected) config.activeFabDescription else config.idleFabDescription
        if (animated && fab.isOrWillBeShown && icon != appliedFabIcon) {
            EntityListAnimations.animateFabSwap(fab, icon, description)
        } else {
            fab.setImageResource(icon)
            fab.contentDescription = activity.getString(description)
            fab.show()
        }
        appliedFabIcon = icon
    }

    private fun selectionTitle(): String =
        activity.resources.getQuantityString(config.titleResForCount, selection.count, selection.count)

    companion object {
        private const val STATE_SELECTED_UUIDS = "selected_uuids"
    }
}
