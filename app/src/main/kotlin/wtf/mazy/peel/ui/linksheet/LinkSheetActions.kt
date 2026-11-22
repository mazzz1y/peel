package wtf.mazy.peel.ui.linksheet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import wtf.mazy.peel.R
import wtf.mazy.peel.model.LinkApp
import wtf.mazy.peel.model.LinkOpeners
import wtf.mazy.peel.util.linkAppIcon
import wtf.mazy.peel.util.linkAppLabel

/**
 * The link sheet's lines of actions: what to do with the link, where to open it, and the image's.
 * The fixed buttons are the layout's; the apps that own the link lead the line of where to open
 * it, before "Incognito", and the default browser follows "Incognito". A line with no button
 * shown is hidden, and the lines with it when none is left.
 */
internal class LinkSheetActions(
    private val lines: ViewGroup,
    hasLink: Boolean,
    hasImage: Boolean,
    private val onApp: (LinkApp) -> Unit,
) {

    private val openLine: ViewGroup = lines.requireViewById(R.id.linkSheetOpenRow)
    private val incognito: View = lines.requireViewById(R.id.linkSheetIncognito)
    private var appButtons = emptyList<View>()

    init {
        incognito.isVisible = hasLink
        button(R.id.linkSheetDownloadImage).isVisible = hasImage
        button(R.id.linkSheetShareImage).isVisible = hasImage
    }

    fun show(openers: LinkOpeners?, canAdd: Boolean, canSearch: Boolean) {
        appButtons.forEach(openLine::removeView)
        val apps = openers?.apps.orEmpty().map(::appButton)
        val browser = openers?.browser?.let(::appButton)
        apps.asReversed().forEach { openLine.addView(it, 0) }
        browser?.let { openLine.addView(it, openLine.indexOfChild(incognito) + 1) }
        appButtons = apps + listOfNotNull(browser)
        button(R.id.linkSheetSystem).isVisible = openers?.asksSystem == true
        button(R.id.linkSheetAdd).isVisible = canAdd
        button(R.id.linkSheetSearch).isVisible = canSearch
        for (line in lines.children) line.isVisible = line.actionRow.children.any { it.isVisible }
        (lines.parent as View).isVisible = lines.children.any { it.isVisible }
    }

    private fun button(id: Int): View = lines.requireViewById(id)

    private fun appButton(app: LinkApp): MaterialButton {
        val context = lines.context
        val button = LayoutInflater.from(context).inflate(R.layout.item_link_sheet_app, openLine, false) as MaterialButton
        val label = context.linkAppLabel(app)
        button.text = label
        button.contentDescription = context.getString(R.string.open_in_app, label)
        button.icon = context.linkAppIcon(app)
        button.setOnClickListener { onApp(app) }
        return button
    }
}
