package wtf.mazy.peel.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.search.SearchView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.searched
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.applyBottomLineFade
import wtf.mazy.peel.ui.common.scrollToTopOnChange
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.UrlField
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.webapplist.SearchBar
import wtf.mazy.peel.ui.webapplist.WebAppPickerAdapter
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.isImeVisible

/** The link a picker opens, and how its web apps are ordered for a link. */
class PickerLink(val initial: UnwrappedLink, val order: (UnwrappedLink) -> List<WebApp>)

/** What a link from another app can do besides opening in a web app. */
class PickerLinkCallbacks(val onIncognito: (UnwrappedLink) -> Unit, val onShare: (UnwrappedLink) -> Unit)

/**
 * The web apps to choose from for a link, laid out as the web-app list's own search in the
 * current search position: the link and its actions on top, then the list's own cards shown as
 * in selection mode, with the search pill above them or the search bar at the foot.
 */
class WebAppPickerSurface(
    private val activity: AppCompatActivity,
    private val link: PickerLink,
    private val groupTitles: Map<String, String>,
    private val onPick: (WebApp, UnwrappedLink) -> Unit,
    private val linkCallbacks: PickerLinkCallbacks?,
    private val onAdd: (UnwrappedLink, onAdded: () -> Unit) -> Unit,
    private val canAdd: (UnwrappedLink) -> Boolean,
) : AppCompatDialog(activity, R.style.AppTheme_PickerSurface) {

    private var current = link.initial
    private var query = ""
    private var results = emptyList<WebApp>()
    private lateinit var field: PickerField
    private val adapter = WebAppPickerAdapter(groupTitles, ::pick)
    private lateinit var emptyState: ListEmptyState
    private lateinit var linkLine: TextView
    private lateinit var addCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bottom = DataManager.globalEffectiveSettings.bottomSearch
        setContentView(if (bottom) R.layout.view_picker_surface_bottom else R.layout.view_picker_surface)
        val window = checkNotNull(window)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val decor = window.decorView
        val list = decor.requireViewById<RecyclerView>(R.id.pickerList)
        emptyState = ListEmptyState(decor.requireViewById(R.id.pickerEmptyState))
        linkLine = decor.requireViewById(R.id.pickerLink)
        addCard = decor.requireViewById(R.id.pickerAdd)
        addCard.setOnClickListener { onAdd(current) { dismiss() } }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        adapter.scrollToTopOnChange { list }
        field = if (bottom) bottomField(decor, list) else topField(decor, list)
        field.onQueryChanged = { text ->
            query = text
            render()
        }
        field.onConfirm = { results.firstOrNull()?.let(::pick) }
        // With the platform back callbacks the dialog receives the back press while the keyboard
        // is still up, so the first one only lowers it.
        onBackPressedDispatcher.addCallback(this) {
            if (!field.lowerKeyboard()) cancel()
        }

        decor.requireViewById<View>(R.id.pickerLinkCard).setOnClickListener { editLink() }
        decor.requireViewById<View>(R.id.pickerLinkActions).isVisible = linkCallbacks != null
        if (linkCallbacks != null) {
            decor.requireViewById<View>(R.id.pickerIncognito).setOnClickListener { finishWith(linkCallbacks.onIncognito) }
            decor.requireViewById<View>(R.id.pickerShare).setOnClickListener { finishWith(linkCallbacks.onShare) }
        }
        showLink()
        render()
    }

    private fun topField(decor: View, list: RecyclerView): PickerField {
        list.applyBottomScreenInsets()
        val searchView = decor.requireViewById<SearchView>(R.id.pickerSearchView)
        ViewCompat.setOnApplyWindowInsetsListener(searchView) { _, insets -> insets }
        searchView.setVisible(true)
        searchView.toolbar.setNavigationOnClickListener { cancel() }
        return TopPickerField(searchView.editText, insetsController(searchView.editText))
    }

    private fun bottomField(decor: View, list: RecyclerView): PickerField {
        val root = decor.requireViewById<View>(R.id.pickerRoot)
        val line = decor.requireViewById<View>(R.id.pickerBottomLine)
        val bar = decor.requireViewById<SearchBar>(R.id.pickerSearchBar)
        val screenBars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(screenBars)
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets.inset(bars.left, bars.top, bars.right, 0)
        }
        line.applyBottomLineFade()
        line.applyBottomScreenInsets()
        list.applyBottomScreenInsets()
        decor.requireViewById<View>(R.id.pickerEmptyFrame).applyBottomScreenInsets()
        return BottomPickerField(bar, insetsController(bar))
    }

    private fun insetsController(view: View) =
        WindowCompat.getInsetsController(checkNotNull(window), view)

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
        addCard.isVisible = canAdd(current)
    }

    private fun editLink() {
        lateinit var input: EditText
        val config = InputDialogConfig(
            hintRes = R.string.url,
            prefill = current.launchUrl,
            onInputReady = { field ->
                input = field
                UrlField.configure(field)
            },
        )
        val dialog = activity.showInputDialogRaw(config) { field, _ ->
            applyLink(field.text.toString().trim())
        }
        UrlField.gate(input, dialog.getButton(BUTTON_POSITIVE), UrlField.FULL)
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
}

private interface PickerField {
    var onQueryChanged: (String) -> Unit
    var onConfirm: () -> Unit

    /** Puts the keyboard down if the field has it up; returns whether there was one. */
    fun lowerKeyboard(): Boolean
}

private class TopPickerField(
    private val field: EditText,
    private val insets: WindowInsetsControllerCompat,
) : PickerField {
    override var onQueryChanged: (String) -> Unit = {}
    override var onConfirm: () -> Unit = {}

    init {
        field.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.doAfterTextChanged { onQueryChanged(it?.toString().orEmpty()) }
        field.setOnEditorActionListener { _, actionId, event ->
            val confirmed = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            if (confirmed) onConfirm()
            confirmed
        }
    }

    override fun lowerKeyboard(): Boolean {
        if (!field.hasFocus() || !field.isImeVisible) return false
        insets.hide(WindowInsetsCompat.Type.ime())
        field.clearFocus()
        return true
    }
}

private class BottomPickerField(
    private val bar: SearchBar,
    private val insets: WindowInsetsControllerCompat,
) : PickerField {
    override var onQueryChanged: (String) -> Unit = {}
    override var onConfirm: () -> Unit = {}

    init {
        bar.onQueryChanged = { onQueryChanged(it) }
        bar.onSearch = { onConfirm() }
        bar.onKeyboardRequested = { insets.show(WindowInsetsCompat.Type.ime()) }
        bar.onActiveChanged = { active -> if (!active) insets.hide(WindowInsetsCompat.Type.ime()) }
    }

    override fun lowerKeyboard(): Boolean {
        if (!bar.hasFocus() || !bar.isImeVisible) return false
        bar.dropFocus()
        insets.hide(WindowInsetsCompat.Type.ime())
        return true
    }
}
