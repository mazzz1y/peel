package wtf.mazy.peel.ui.linksheet

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import wtf.mazy.peel.R
import wtf.mazy.peel.model.DataManager
import wtf.mazy.peel.model.LinkChoice
import wtf.mazy.peel.model.LinkOrigin
import wtf.mazy.peel.model.LinkSheetRow
import wtf.mazy.peel.model.LinkSheetRows
import wtf.mazy.peel.model.LinkSubject
import wtf.mazy.peel.model.LinkTarget
import wtf.mazy.peel.model.MediaKind
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.model.hasWebAppAt
import wtf.mazy.peel.ui.common.ListEmptyState
import wtf.mazy.peel.ui.common.scrollToTopOnChange
import wtf.mazy.peel.ui.dialog.InputDialogConfig
import wtf.mazy.peel.ui.dialog.UrlField
import wtf.mazy.peel.ui.dialog.dismissOnDestroyOf
import wtf.mazy.peel.ui.dialog.showInputDialogRaw
import wtf.mazy.peel.ui.entitylist.PendingDeletes
import wtf.mazy.peel.ui.webapplist.SearchBar
import wtf.mazy.peel.util.applyBottomScreenInsets
import wtf.mazy.peel.util.copyToClipboard
import wtf.mazy.peel.util.isSingleWindowHost
import wtf.mazy.peel.util.shouldOfferOpenInSystem
import wtf.mazy.peel.util.sortedByAffinity

/**
 * What to do with a link or a piece of media, as a sheet rising from the bottom like the system's
 * share sheet: what was pressed, its actions, then the web apps to open a web link in, shown as
 * the web-app list's cards in selection mode. Searching takes the sheet to full height and puts
 * the search bar in place of the target until the search ends. On TV and car screens it fills the
 * screen. It is Peel's own surface, so it takes Peel's theme even when a web app's window, with its
 * own surface colours, opens it. Every opening ends in exactly one [LinkChoice].
 */
class LinkSheet private constructor(
    private val activity: AppCompatActivity,
    private val subject: LinkSubject,
    private val origin: LinkOrigin,
    private val everyApp: List<WebApp>,
    private val groupTitles: Map<String, String>,
    private val onChoice: (LinkChoice) -> Unit,
) : BottomSheetDialog(ContextThemeWrapper(activity, R.style.AppTheme), R.style.AppTheme_LinkSheet) {

    private var target = LinkTarget.of(subject)
    private val candidates = LinkSheetRows.candidates(everyApp, origin)
    private var ordered = emptyList<WebApp>()
    private var query = ""
    private var rows = emptyList<LinkSheetRow>()
    private var chosen: LinkChoice = LinkChoice.Dismissed
    private val adapter = LinkSheetAdapter(groupTitles, ::pick)
    private lateinit var decor: View

    /** The web apps and their search, when there is a list at all. */
    private var apps: Apps? = null

    private class Apps(val emptyState: ListEmptyState, val bar: SearchBar, val searchBack: OnBackPressedCallback)

    /** The link every choice uses, as last edited; null for media that is not a link. */
    private val link: UnwrappedLink? get() = target.link

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_link_sheet)
        decor = checkNotNull(window).decorView
        bindTarget()
        // Without a web link, or with nothing to open it in, the sheet has no list to search.
        val listed = link?.takeIf { LinkSheetRows.hasRows(candidates, origin) }
        bindActions(searchable = listed != null)
        listed?.let(::bindApps)
        setOnDismissListener { onChoice(chosen) }
        setUpSheet(withApps = listed != null)
    }

    private fun <V : View> view(id: Int): V = decor.requireViewById(id)

    private fun onClick(id: Int, action: () -> Unit) = view<View>(id).setOnClickListener { action() }

    private fun bindTarget() {
        view<ImageView>(R.id.linkSheetTargetIcon).setImageResource(subjectIcon())
        val targetView = view<View>(R.id.linkSheetTarget)
        // On the whole target rather than the title alone, which would take the target's taps.
        targetView.setOnLongClickListener {
            val title = target.title ?: return@setOnLongClickListener false
            context.copyToClipboard(title, R.string.text_copied)
            true
        }
        if (link != null) targetView.setOnClickListener { editLink() } else targetView.isClickable = false
        onClick(R.id.linkSheetShare) { target.shareable?.let { choose(LinkChoice.Share(it, target.title)) } }
        showTarget()
    }

    @DrawableRes
    private fun subjectIcon(): Int = when (subject) {
        is LinkSubject.Web -> R.drawable.ic_symbols_link_24
        is LinkSubject.Media -> when (subject.kind) {
            MediaKind.IMAGE -> R.drawable.ic_symbols_image_wght300_24
            MediaKind.VIDEO -> R.drawable.ic_symbols_movie_wght300_24
            MediaKind.AUDIO -> R.drawable.ic_symbols_music_note_wght300_24
        }
    }

    private fun showTarget() {
        val title = target.title
        view<TextView>(R.id.linkSheetTargetTitle).text = title ?: target.shownAddress
        view<TextView>(R.id.linkSheetTargetAddress).apply {
            text = target.shownAddress
            isVisible = title != null
        }
        view<View>(R.id.linkSheetShare).isVisible = target.shareable != null
    }

    private fun bindActions(searchable: Boolean) {
        val image = subject.image
        view<View>(R.id.linkSheetActions).isVisible = link != null || image != null
        view<View>(R.id.linkSheetSearch).isVisible = link != null && searchable
        view<View>(R.id.linkSheetIncognito).isVisible = link != null
        view<View>(R.id.linkSheetDownloadImage).isVisible = image != null
        view<View>(R.id.linkSheetShareImage).isVisible = image != null
        if (image != null) {
            onClick(R.id.linkSheetDownloadImage) { choose(LinkChoice.DownloadImage(image)) }
            onClick(R.id.linkSheetShareImage) { choose(LinkChoice.ShareImage(image)) }
        }
        onClick(R.id.linkSheetSearch, ::startSearch)
        onClick(R.id.linkSheetIncognito) { chooseForLink { LinkChoice.Incognito(it.launchUrl) } }
        onClick(R.id.linkSheetSystem) { chooseForLink { LinkChoice.System(it.launchUrl) } }
        onClick(R.id.linkSheetAdd) { chooseForLink { LinkChoice.Add(it.destination) } }
        showLinkActions()
    }

    private fun showLinkActions() {
        val current = link
        view<View>(R.id.linkSheetSystem).isVisible =
            current != null && context.shouldOfferOpenInSystem(current.launchUrl)
        view<View>(R.id.linkSheetAdd).isVisible =
            current != null && !everyApp.hasWebAppAt(current.destination, DataManager::effectiveSandbox)
    }

    private fun bindApps(current: UnwrappedLink) {
        val list = view<RecyclerView>(R.id.linkSheetList)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.addItemDecoration(CurrentWindowDivider(context, adapter::endsCurrentWindow))
        adapter.scrollToTopOnChange { list }
        list.applyBottomScreenInsets()
        val bar = view<SearchBar>(R.id.linkSheetSearchBar)
        bar.bindKeyboard(WindowCompat.getInsetsController(checkNotNull(window), bar))
        bar.onQueryChanged = { text ->
            query = text
            render()
        }
        bar.onSearch = { rows.firstOrNull()?.let(::pick) }
        bar.onActiveChanged = ::showSearch
        // Only a search claims back, so the sheet keeps its own predictive back otherwise.
        // Enabling the callback re-registers it after the sheet's, which is what lets it win.
        val searchBack = onBackPressedDispatcher.addCallback(this, enabled = false) {
            if (!bar.lowerKeyboard()) bar.close()
        }
        apps = Apps(ListEmptyState(view(R.id.linkSheetEmptyState)), bar, searchBack)
        reorder(current)
    }

    private fun setUpSheet(withApps: Boolean) {
        val sheet = view<View>(MaterialR.id.design_bottom_sheet)
        // TV and car screens are driven without touch, so there is nothing to drag.
        val singleWindow = context.isSingleWindowHost()
        view<View>(R.id.linkSheetHandle).isVisible = !singleWindow
        behavior.isDraggable = !singleWindow
        view<View>(R.id.linkSheetApps).isVisible = withApps
        if (!withApps) {
            if (singleWindow) sheet.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
            val root = view<View>(R.id.linkSheetRoot)
            root.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            root.applyBottomScreenInsets()
            behavior.skipCollapsed = true
            behavior.state = STATE_EXPANDED
            return
        }
        sheet.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
        // Resting below full height is the collapsed state, as in the system share sheet: a
        // drag down from full height comes back to it, and only a drag well past it closes.
        behavior.apply {
            peekHeight = restingHeight(context.resources.displayMetrics.heightPixels)
            skipCollapsed = false
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
        val bar = apps?.bar ?: return
        behavior.state = STATE_EXPANDED
        bar.isVisible = true
        bar.focusQuery()
    }

    private fun showSearch(active: Boolean) {
        val apps = apps ?: return
        apps.bar.isVisible = active
        view<View>(R.id.linkSheetHeader).isVisible = !active
        apps.searchBack.isEnabled = active
    }

    private fun reorder(current: UnwrappedLink) {
        ordered = candidates.sortedByAffinity(current.destination)
        render()
    }

    private fun render() {
        val apps = apps ?: return
        rows = LinkSheetRows.rows(ordered, origin, query, groupTitles)
        adapter.submit(rows)
        apps.emptyState.render(rows.isEmpty())
    }

    private fun editLink() {
        val current = link ?: return
        lateinit var input: EditText
        val config = InputDialogConfig(
            hintRes = R.string.url,
            prefill = current.launchUrl,
            url = true,
            accepts = UrlField.FULL,
            onInputReady = { input = it },
        )
        val dialog = activity.showInputDialogRaw(config) { field, _ ->
            applyLink(Redirectors.unwrap(field.text.toString().trim()))
        }
        input.requestFocus()
        dialog.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun applyLink(edited: UnwrappedLink) {
        target = target.edited(edited)
        showTarget()
        showLinkActions()
        reorder(edited)
    }

    private fun pick(row: LinkSheetRow) = chooseForLink { current ->
        when (row) {
            is LinkSheetRow.App -> LinkChoice.App(row.app, current.launchUrl)
            is LinkSheetRow.CurrentWindow -> LinkChoice.CurrentWindow(current.launchUrl)
        }
    }

    private fun chooseForLink(choice: (UnwrappedLink) -> LinkChoice) {
        link?.let { choose(choice(it)) }
    }

    private fun choose(choice: LinkChoice) {
        chosen = choice
        dismiss()
    }

    companion object {
        private const val SHEET_RATIO = 0.75f

        /**
         * Asks what to do with [subject], opened from [origin]. [onChoice] gets exactly one
         * answer, after the sheet is gone; closing it answers [LinkChoice.Dismissed].
         */
        fun show(
            activity: AppCompatActivity,
            subject: LinkSubject,
            origin: LinkOrigin,
            onChoice: (LinkChoice) -> Unit,
        ) {
            activity.lifecycleScope.launch {
                val pending = PendingDeletes.webApps
                val everyApp = if (subject is LinkSubject.Web) {
                    DataManager.queryAllWebApps().filterNot { it.uuid in pending }
                } else emptyList()
                if (activity.isFinishing) {
                    onChoice(LinkChoice.Dismissed)
                    return@launch
                }
                val groupTitles = DataManager.groups.associate { it.uuid to it.title }
                LinkSheet(activity, subject, origin, everyApp, groupTitles, onChoice).run {
                    show()
                    dismissOnDestroyOf(activity)
                }
            }
        }
    }
}
