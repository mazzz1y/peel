package wtf.mazy.peel.ui

import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.search.SearchView
import wtf.mazy.peel.R
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.searched
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.scrollToTopOnChange
import wtf.mazy.peel.ui.dialog.InitialSelection
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.webapplist.WebAppPickerAdapter
import wtf.mazy.peel.util.WebOrigin
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.isImeVisible

/** The link a picker opens, and how its web apps are ordered for a link. */
class PickerLink(val initial: UnwrappedLink, val order: (UnwrappedLink) -> List<WebApp>)

/**
 * The web apps to choose from for a link, laid out as the web-app list's own contained search:
 * the search pill at the top over the link and its actions, then the list's own cards, shown as
 * in selection mode.
 */
class WebAppPickerSurface(
    private val activity: AppCompatActivity,
    private val link: PickerLink,
    private val groupTitles: Map<String, String>,
    private val onPick: (WebApp, UnwrappedLink) -> Unit,
    private val onIncognito: (UnwrappedLink) -> Unit,
    private val onShare: (UnwrappedLink) -> Unit,
    private val onAdd: (UnwrappedLink, onAdded: () -> Unit) -> Unit,
    private val canAdd: (UnwrappedLink) -> Boolean,
) : AppCompatDialog(activity, R.style.AppTheme_PickerSurface) {

    private var current = link.initial
    private var query = ""
    private var results = emptyList<WebApp>()
    private val adapter = WebAppPickerAdapter(groupTitles, ::pick)
    private lateinit var emptyState: ListEmptyState
    private lateinit var linkLine: TextView
    private lateinit var addCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_picker_surface)
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
        list.applyBottomScreenInsets()

        val searchView = decor.requireViewById<SearchView>(R.id.pickerSearchView)
        ViewCompat.setOnApplyWindowInsetsListener(searchView) { _, insets -> insets }
        searchView.setVisible(true)
        searchView.toolbar.setNavigationOnClickListener { cancel() }
        val field = searchView.editText
        // With the platform back callbacks the dialog receives the back press while the keyboard
        // is still up, so the first one only lowers it.
        onBackPressedDispatcher.addCallback(this) {
            if (field.hasFocus() && field.isImeVisible) {
                WindowCompat.getInsetsController(window, field).hide(WindowInsetsCompat.Type.ime())
                field.clearFocus()
            } else {
                cancel()
            }
        }

        decor.requireViewById<View>(R.id.pickerLinkCard).setOnClickListener { editLink() }
        decor.requireViewById<View>(R.id.pickerIncognito).setOnClickListener { finishWith(onIncognito) }
        decor.requireViewById<View>(R.id.pickerShare).setOnClickListener { finishWith(onShare) }
        showLink()

        field.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            render()
        }
        field.setOnEditorActionListener { _, actionId, event ->
            val confirmed = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            if (confirmed) results.firstOrNull()?.let(::pick)
            confirmed
        }
        render()
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
        addCard.isVisible = canAdd(current)
    }

    private fun editLink() {
        lateinit var input: EditText
        val config = InputDialogConfig(
            hintRes = R.string.url,
            prefill = current.launchUrl,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            initialSelection = InitialSelection.CURSOR_AT_END,
            onInputReady = { field ->
                input = field
                field.setHorizontallyScrolling(false)
                field.maxLines = LINK_EDITOR_MAX_LINES
                field.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            },
        )
        val dialog = activity.showInputDialogRaw(config) { field, _ ->
            applyLink(field.text.toString().trim())
        }
        val ok = dialog.getButton(BUTTON_POSITIVE)
        ok.isEnabled = input.text.toString().trim().isWebAddress()
        input.doAfterTextChanged { ok.isEnabled = it.toString().trim().isWebAddress() }
        input.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE && ok.isEnabled).also { if (it) ok.performClick() }
        }
        input.requestFocus()
        dialog.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun String.isWebAddress(): Boolean = WebOrigin.of(this) != null

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
        const val LINK_EDITOR_MAX_LINES = 6
    }
}
