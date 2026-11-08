package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
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
import wtf.mazy.peel.model.matchesQuery
import wtf.mazy.peel.ui.common.showIcon
import wtf.mazy.peel.ui.dialog.InitialSelection
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.entitylist.EntityRowAnimator
import wtf.mazy.peel.ui.entitylist.EntityRowView
import wtf.mazy.peel.ui.webapplist.WebAppIndicators
import wtf.mazy.peel.util.WebOrigin
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.isImeVisible
import wtf.mazy.peel.util.prettyBaseUrl

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
    private val adapter = Adapter(link.order(current))
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
        val empty = decor.requireViewById<View>(R.id.pickerEmptyState)
        linkLine = decor.requireViewById(R.id.pickerLink)
        addCard = decor.requireViewById(R.id.pickerAdd)
        addCard.setOnClickListener { onAdd(current) { dismiss() } }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
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
            adapter.filter(text?.toString().orEmpty())
            list.scrollToPosition(0)
            val none = adapter.itemCount == 0
            list.isVisible = !none
            empty.isVisible = none
        }
        field.setOnEditorActionListener { _, actionId, event ->
            val confirmed = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            if (confirmed) adapter.pickFirst()
            confirmed
        }
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
        adapter.reorder(link.order(current))
    }

    private fun pick(app: WebApp) {
        dismiss()
        onPick(app, current)
    }

    private inner class Adapter(private var apps: List<WebApp>) : RecyclerView.Adapter<Holder>() {
        private var query = ""
        private var shown: List<WebApp> = apps

        fun filter(query: String) {
            this.query = query
            refresh()
        }

        fun reorder(apps: List<WebApp>) {
            this.apps = apps
            refresh()
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun refresh() {
            shown = apps.filter { it.matchesQuery(query, groupTitles) }
            notifyDataSetChanged()
        }

        fun pickFirst() {
            shown.firstOrNull()?.let(::pick)
        }

        override fun getItemCount(): Int = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.web_app_list_item, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(shown[position])
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view), EntityRowView {
        override val itemIcon: ImageView = view.requireViewById(R.id.item_icon)
        override val menuButton: View = view.requireViewById(R.id.item_menu)
        private val webAppIndicators = WebAppIndicators(view)
        override val indicators: List<ImageView> = webAppIndicators.views
        private val title: TextView = view.requireViewById(R.id.item_primary)
        private val url: TextView = view.requireViewById(R.id.item_secondary)
        private val group: TextView = view.requireViewById(R.id.item_tertiary)

        init {
            menuButton.isEnabled = false
            menuButton.isClickable = false
            menuButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bind(app: WebApp) {
            itemIcon.showIcon(app.resolveIcon())
            title.text = app.title
            url.text = prettyBaseUrl(app.baseUrl)
            val groupTitle = app.groupUuid?.let(groupTitles::get)
            group.text = groupTitle
            group.isVisible = groupTitle != null
            webAppIndicators.bind(app)
            EntityRowAnimator.applyModeState(this, inSelectionMode = true)
            itemView.setOnClickListener { pick(app) }
        }
    }

    private companion object {
        const val LINK_EDITOR_MAX_LINES = 6
    }
}
