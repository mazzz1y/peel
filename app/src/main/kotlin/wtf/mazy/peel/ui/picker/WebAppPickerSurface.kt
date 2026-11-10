package wtf.mazy.peel.ui.picker

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetDialog
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.searched
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.scrollToTopOnChange
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.UrlField
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.webapplist.SearchBar
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.isSingleWindowHost
import com.google.android.material.R as MaterialR

/**
 * The web apps to choose from for a link, as a sheet rising from the bottom like the system's
 * share sheet: the link and its actions, then the web-app list's own cards shown as in selection
 * mode. Searching takes the sheet to full height and puts the search bar in place of the link
 * until the search ends. On TV and car screens it fills the screen. It is Peel's own surface, so
 * it takes Peel's theme even when a web app's window, with its own surface colours, opens it.
 */
class WebAppPickerSurface(
    private val activity: AppCompatActivity,
    private val link: PickerLink,
    private val groupTitles: Map<String, String>,
    private val onPick: (WebApp, UnwrappedLink) -> Unit,
    private val linkCallbacks: PickerLinkCallbacks?,
    private val onAdd: (UnwrappedLink, onAdded: () -> Unit) -> Unit,
    private val canAdd: (UnwrappedLink) -> Boolean,
) : BottomSheetDialog(ContextThemeWrapper(activity, R.style.AppTheme), R.style.AppTheme_PickerSurface) {

    private var current = link.initial
    private var query = ""
    private var results = emptyList<WebApp>()
    private val adapter = WebAppPickerAdapter(groupTitles, ::pick)
    private lateinit var emptyState: ListEmptyState
    private lateinit var linkLine: TextView
    private lateinit var addButton: View
    private lateinit var header: View
    private lateinit var bar: SearchBar
    private lateinit var searchBack: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_picker_surface)
        val decor = checkNotNull(window).decorView
        val list = decor.requireViewById<RecyclerView>(R.id.pickerList)
        emptyState = ListEmptyState(decor.requireViewById(R.id.pickerEmptyState))
        linkLine = decor.requireViewById(R.id.pickerLink)
        header = decor.requireViewById(R.id.pickerHeader)
        addButton = decor.requireViewById(R.id.pickerAdd)
        addButton.setOnClickListener { onAdd(current) { dismiss() } }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        adapter.scrollToTopOnChange { list }
        list.applyBottomScreenInsets()
        bar = decor.requireViewById(R.id.pickerSearchBar)
        bar.bindKeyboard(WindowCompat.getInsetsController(checkNotNull(window), bar))
        bar.onQueryChanged = { text ->
            query = text
            render()
        }
        bar.onSearch = { results.firstOrNull()?.let(::pick) }
        bar.onActiveChanged = ::showSearch
        // Only a search claims back, so the sheet keeps its own predictive back otherwise.
        // Enabling the callback re-registers it after the sheet's, which is what lets it win.
        searchBack = onBackPressedDispatcher.addCallback(this, enabled = false) {
            if (!bar.lowerKeyboard()) bar.close()
        }
        decor.requireViewById<View>(R.id.pickerSearch).setOnClickListener { startSearch() }

        decor.requireViewById<View>(R.id.pickerLinkCard).setOnClickListener { editLink() }
        val incognito = decor.requireViewById<View>(R.id.pickerIncognito)
        val share = decor.requireViewById<View>(R.id.pickerShare)
        incognito.isVisible = linkCallbacks != null
        share.isVisible = linkCallbacks != null
        if (linkCallbacks != null) {
            incognito.setOnClickListener { finishWith(linkCallbacks.onIncognito) }
            share.setOnClickListener { finishWith(linkCallbacks.onShare) }
        }
        showLink()
        render()
        setUpSheet(decor)
    }

    private fun setUpSheet(decor: View) {
        val sheet = decor.requireViewById<View>(MaterialR.id.design_bottom_sheet)
        sheet.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
        // TV and car screens are driven without touch, so there is nothing to drag.
        val singleWindow = context.isSingleWindowHost()
        decor.requireViewById<View>(R.id.pickerHandle).isVisible = !singleWindow
        // Resting below full height is the collapsed state, as in the system share sheet: a
        // drag down from full height comes back to it, and only a drag well past it closes.
        behavior.apply {
            peekHeight = restingHeight(context.resources.displayMetrics.heightPixels)
            skipCollapsed = false
            isDraggable = !singleWindow
            state = if (singleWindow) STATE_EXPANDED else STATE_COLLAPSED
        }
        // A web app's window rotates without being recreated, so the resting height follows the
        // space the sheet has. Changing it relayouts the sheet, which must wait for this pass.
        (sheet.parent as View).addOnLayoutChangeListener { parent, _, _, _, _, _, _, _, _ ->
            val peek = restingHeight(parent.height)
            if (peek != behavior.peekHeight) parent.post { behavior.peekHeight = peek }
        }
    }

    private fun restingHeight(space: Int) = (space * SHEET_RATIO).toInt()

    private fun startSearch() {
        behavior.state = STATE_EXPANDED
        bar.isVisible = true
        bar.focusQuery()
    }

    private fun showSearch(active: Boolean) {
        bar.isVisible = active
        header.isVisible = !active
        searchBack.isEnabled = active
    }

    private fun render() {
        results = link.order(current).searched(query, groupTitles)
        adapter.submit(results)
        emptyState.render(results.isEmpty())
    }

    private fun finishWith(action: (UnwrappedLink) -> Unit) {
        action(current)
        dismiss()
    }

    private fun showLink() {
        linkLine.text = current.destination
        addButton.isVisible = canAdd(current)
    }

    private fun editLink() {
        lateinit var input: EditText
        val config = InputDialogConfig(
            hintRes = R.string.url,
            prefill = current.launchUrl,
            url = true,
            accepts = UrlField.FULL,
            onInputReady = { input = it },
        )
        val dialog = activity.showInputDialogRaw(config) { field, _ ->
            applyLink(field.text.toString().trim())
        }
        input.requestFocus()
        dialog.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun applyLink(edited: String) {
        current = Redirectors.unwrap(edited)
        showLink()
        render()
    }

    private fun pick(app: WebApp) {
        dismiss()
        onPick(app, current)
    }

    private companion object {
        const val SHEET_RATIO = 0.75f
    }
}
