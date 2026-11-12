package wtf.mazy.peel.ui.linksheet

import android.view.View
import androidx.annotation.DrawableRes
import wtf.mazy.peel.R
import wtf.mazy.peel.model.LinkOrigin
import wtf.mazy.peel.model.LinkSheetRow
import wtf.mazy.peel.model.StableIdRegistry
import wtf.mazy.peel.ui.entitylist.EntityBinder
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowAnimator
import wtf.mazy.peel.ui.entitylist.EntityRowListener
import wtf.mazy.peel.ui.entitylist.EntityRowView
import wtf.mazy.peel.ui.webapplist.WebAppRowHolder

/**
 * The web-app list's cards for choosing where a link opens: every row is shown as in selection
 * mode, without its menu, and tapping the row or its icon picks it.
 */
internal class LinkSheetAdapter(
    private val groupTitles: Map<String, String>,
    onPick: (LinkSheetRow) -> Unit,
) : EntityListAdapter<LinkSheetRow, WebAppRowHolder>(
    binder = RowBinder,
    actions = PickListener(onPick),
) {

    override fun layoutRes(): Int = R.layout.web_app_list_item
    override fun createViewHolder(view: View): WebAppRowHolder = WebAppRowHolder(view).apply {
        menuButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun bindRow(holder: WebAppRowHolder, row: EntityRow<LinkSheetRow>) {
        when (val entity = row.entity) {
            is LinkSheetRow.App -> holder.bind(entity.app, row.tertiaryText)
            is LinkSheetRow.CurrentWindow -> {
                val context = holder.itemView.context
                val currentWindow = context.getString(R.string.current_window)
                when (val window = entity.window) {
                    is LinkOrigin.Window.App -> holder.bind(window.app, null, currentWindow)
                    LinkOrigin.Window.PrivateSession ->
                        holder.bindWindow(context.getString(R.string.private_session), currentWindow, private = true)

                    is LinkOrigin.Window.Extension -> holder.bindWindow(window.title, currentWindow, private = false)
                }
            }
        }
    }

    /** Whether the current window's row is at [position] with web apps after it. */
    fun endsCurrentWindow(position: Int): Boolean =
        position in 0 until itemCount - 1 && getItem(position).entity is LinkSheetRow.CurrentWindow

    fun submit(rows: List<LinkSheetRow>) {
        submitRows(rows.map { row ->
            EntityRow(
                entity = row,
                selected = false,
                inSelectionMode = true,
                tertiaryText = (row as? LinkSheetRow.App)?.app?.groupUuid?.let(groupTitles::get),
                iconStamp = row.ownerApp?.iconStamp ?: 0L,
            )
        })
    }

    private object RowBinder : EntityBinder<LinkSheetRow> {
        override fun uuid(item: LinkSheetRow): String = when (item) {
            is LinkSheetRow.App -> item.app.uuid
            is LinkSheetRow.CurrentWindow -> CURRENT_WINDOW_KEY
        }

        override fun stableId(item: LinkSheetRow): Long = when (item) {
            is LinkSheetRow.App -> item.app.stableId
            is LinkSheetRow.CurrentWindow -> StableIdRegistry.idFor(CURRENT_WINDOW_KEY)
        }

        override fun bindIcon(host: EntityRowView, item: LinkSheetRow, selected: Boolean) {
            val app = item.ownerApp
            if (app != null) {
                EntityRowAnimator.applyIconState(host, app, selected)
                return
            }
            host.itemIcon.background = null
            host.itemIcon.setImageResource(windowIcon(item))
        }

        override fun contentEquals(a: LinkSheetRow, b: LinkSheetRow): Boolean {
            val appA = a.ownerApp
            val appB = b.ownerApp
            return if (appA != null && appB != null) {
                a::class == b::class && appA.contentFingerprint == appB.contentFingerprint
            } else a == b
        }

        @DrawableRes
        private fun windowIcon(item: LinkSheetRow): Int =
            when ((item as? LinkSheetRow.CurrentWindow)?.window) {
                LinkOrigin.Window.PrivateSession -> R.drawable.ic_symbols_visibility_off_24
                else -> R.drawable.ic_symbols_extension_24
            }

        private const val CURRENT_WINDOW_KEY = "link-sheet-current-window"
    }

    private class PickListener(private val onPick: (LinkSheetRow) -> Unit) : EntityRowListener<LinkSheetRow> {
        override fun onItemClick(item: LinkSheetRow) = onPick(item)
        override fun onItemIconClick(item: LinkSheetRow) = onPick(item)
        override fun onItemMenu(view: View, item: LinkSheetRow) = Unit
    }
}

private val LinkSheetRow.ownerApp
    get() = when (this) {
        is LinkSheetRow.App -> app
        is LinkSheetRow.CurrentWindow -> (window as? LinkOrigin.Window.App)?.app
    }
