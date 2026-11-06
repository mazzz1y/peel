package wtf.mazy.peel.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.matchesQuery
import wtf.mazy.peel.ui.common.showIcon
import wtf.mazy.peel.ui.entitylist.EntityRowAnimator
import wtf.mazy.peel.ui.entitylist.EntityRowView
import wtf.mazy.peel.ui.webapplist.UrlSuggestion
import wtf.mazy.peel.ui.webapplist.UrlSuggestionStrip
import wtf.mazy.peel.ui.webapplist.WebAppIndicators
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.isImeVisible
import wtf.mazy.peel.util.prettyBaseUrl

/**
 * A long list of web apps to choose from, laid out as the web-app list's own contained search:
 * the search pill at the top over the list's own cards, shown as in selection mode.
 */
class WebAppPickerSurface(
    activity: AppCompatActivity,
    private val apps: List<WebApp>,
    private val groupTitles: Map<String, String>,
    private val onPick: (WebApp) -> Unit,
    private val onIncognito: (() -> Unit)?,
) : AppCompatDialog(activity, R.style.AppTheme_PickerSurface) {

    private val adapter = Adapter()

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
        val suggestions = decor.requireViewById<UrlSuggestionStrip>(R.id.pickerSuggestions)
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

        if (onIncognito != null) {
            suggestions.animated = false
            suggestions.render(setOf(UrlSuggestion.OPEN_PRIVATE))
            suggestions.onSuggestion = {
                onIncognito()
                dismiss()
            }
        }

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

    private fun pick(app: WebApp) {
        dismiss()
        onPick(app)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var shown: List<WebApp> = apps

        @SuppressLint("NotifyDataSetChanged")
        fun filter(query: String) {
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
}
