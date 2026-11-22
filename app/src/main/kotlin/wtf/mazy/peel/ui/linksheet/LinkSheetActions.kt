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
import wtf.mazy.peel.model.LinkOrigin
import wtf.mazy.peel.ui.common.iconDrawable
import wtf.mazy.peel.util.linkAppIcon
import wtf.mazy.peel.util.linkAppLabel

/**
 * The link sheet's lines of actions: what to do with the link, where to open it, and the image's.
 * The fixed buttons are the layout's; the window the link came from leads the line of where to
 * open it, the apps that own the link come before "Incognito", and the default browser follows
 * it. A line with no button shown is hidden, and the lines with it when none is left.
 */
internal class LinkSheetActions(
    private val lines: ViewGroup,
    hasLink: Boolean,
    hasImage: Boolean,
    window: LinkOrigin.Window?,
    private val onApp: (LinkApp) -> Unit,
) {

    private val openLine: ViewGroup = lines.requireViewById(R.id.linkSheetOpenRow)
    private val incognito: View = lines.requireViewById(R.id.linkSheetIncognito)
    private var appButtons = emptyList<View>()

    init {
        if (hasLink && window != null) showWindow(window)
        // In a private session, opening here is already incognito.
        incognito.isVisible = hasLink && window != LinkOrigin.Window.PrivateSession
        button(R.id.linkSheetDownloadImage).isVisible = hasImage
        button(R.id.linkSheetShareImage).isVisible = hasImage
    }

    fun show(openers: LinkOpeners?, canAdd: Boolean, canSearch: Boolean) {
        appButtons.forEach(openLine::removeView)
        val apps = openers?.apps.orEmpty().map(::appButton)
        val browser = openers?.browser?.let(::appButton)
        apps.forEach { openLine.addView(it, openLine.indexOfChild(incognito)) }
        browser?.let { openLine.addView(it, openLine.indexOfChild(incognito) + 1) }
        appButtons = apps + listOfNotNull(browser)
        button(R.id.linkSheetSystem).isVisible = openers?.asksSystem == true
        button(R.id.linkSheetAdd).isVisible = canAdd
        button(R.id.linkSheetSearch).isVisible = canSearch
        for (line in lines.children) line.isVisible = line.actionRow.children.any { it.isVisible }
        (lines.parent as View).isVisible = lines.children.any { it.isVisible }
    }

    private fun button(id: Int): View = lines.requireViewById(id)

    private fun showWindow(window: LinkOrigin.Window) {
        val button = lines.requireViewById<MaterialButton>(R.id.linkSheetThisWindow)
        button.isVisible = true
        when (window) {
            is LinkOrigin.Window.App -> {
                button.icon = lines.resources.iconDrawable(window.app.resolveIcon(button.iconSize))
                button.iconTint = null
            }

            LinkOrigin.Window.PrivateSession -> button.setIconResource(R.drawable.ic_symbols_visibility_off_24)
            LinkOrigin.Window.Extension -> button.setIconResource(R.drawable.ic_symbols_extension_24)
        }
    }

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
