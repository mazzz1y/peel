package wtf.mazy.peel.ui.linksheet

import android.view.View
import androidx.core.view.isVisible
import wtf.mazy.peel.R
import wtf.mazy.peel.model.WebApp
import wtf.mazy.peel.ui.entitylist.EntityListAdapter
import wtf.mazy.peel.ui.entitylist.EntityRow
import wtf.mazy.peel.ui.entitylist.EntityRowListener
import wtf.mazy.peel.ui.entitylist.binders.WebAppBinder
import wtf.mazy.peel.ui.webapplist.WebAppRowHolder

/**
 * The web-app list's cards for choosing where a link opens: every row is shown as in selection
 * mode, without its menu, and tapping the row or its icon picks it.
 */
internal class LinkSheetAdapter(
    private val groupTitles: Map<String, String>,
    onPick: (WebApp) -> Unit,
) : EntityListAdapter<WebApp, WebAppRowHolder>(
    binder = WebAppBinder,
    actions = PickListener(onPick),
) {

    override fun layoutRes(): Int = R.layout.web_app_list_item
    override fun createViewHolder(view: View): WebAppRowHolder = WebAppRowHolder(view).apply {
        menuButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Beside the installed apps above, Peel's web apps carry its mark.
        view.findViewById<View>(R.id.item_badge).isVisible = true
    }

    override fun bindRow(holder: WebAppRowHolder, row: EntityRow<WebApp>) {
        holder.bind(row.entity, row.tertiaryText)
    }

    fun submit(apps: List<WebApp>) {
        submitRows(apps.map { app ->
            EntityRow(
                entity = app,
                selected = false,
                inSelectionMode = true,
                tertiaryText = app.groupUuid?.let(groupTitles::get),
            )
        })
    }

    private class PickListener(private val onPick: (WebApp) -> Unit) : EntityRowListener<WebApp> {
        override fun onItemClick(item: WebApp) = onPick(item)
        override fun onItemIconClick(item: WebApp) = onPick(item)
        override fun onItemMenu(view: View, item: WebApp) = Unit
    }
}
