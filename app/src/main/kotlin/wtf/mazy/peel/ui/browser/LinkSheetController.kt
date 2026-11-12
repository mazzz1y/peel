package wtf.mazy.peel.ui.browser

import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import wtf.mazy.peel.R
import wtf.mazy.peel.model.LinkChoice
import wtf.mazy.peel.model.LinkOrigin
import wtf.mazy.peel.model.LinkSubject
import wtf.mazy.peel.model.LongPress
import wtf.mazy.peel.model.MediaKind
import wtf.mazy.peel.model.Redirectors
import wtf.mazy.peel.ui.linksheet.LinkSheet
import wtf.mazy.peel.ui.linksheet.openElsewhere
import wtf.mazy.peel.util.copyToClipboard

/** A window's link sheet: what it asks about the links the window's pages lead to or press. */
class LinkSheetController(
    private val activity: AppCompatActivity,
    private val window: () -> LinkOrigin.Window,
    private val ownerUuid: () -> String?,
    private val openHere: (String) -> Unit,
    private val downloadImage: (String) -> Unit,
    private val shareImage: (String) -> Unit,
) {

    /** Asks about a navigation to [url] that this window was about to make. */
    fun askAbout(url: String, onChoice: (LinkChoice) -> Unit) = show(url, window(), onChoice)

    /** Offers the page this window shows to open anywhere but in its own web app. */
    fun offerPage(url: String) = show(url, LinkOrigin.Page(ownerUuid()), ::perform)

    fun onLongPress(element: ContextElement) {
        val press = LongPress.of(
            linkUri = element.linkUri,
            linkText = element.linkText,
            altText = element.altText,
            title = element.title,
            media = element.mediaKind,
            srcUri = element.srcUri,
        ) ?: return
        when (press) {
            is LongPress.Copy -> activity.copyToClipboard(press.text, R.string.text_copied)
            is LongPress.Sheet -> LinkSheet.show(activity, press.subject, window(), ::perform)
        }
    }

    private fun show(url: String, origin: LinkOrigin, onChoice: (LinkChoice) -> Unit) =
        LinkSheet.show(activity, LinkSubject.Web(Redirectors.unwrap(url)), origin, onChoice)

    private fun perform(choice: LinkChoice) {
        when (choice) {
            is LinkChoice.CurrentWindow -> openHere(choice.url)
            is LinkChoice.Elsewhere -> activity.openElsewhere(choice)
            is LinkChoice.DownloadImage -> downloadImage(choice.url)
            is LinkChoice.ShareImage -> shareImage(choice.url)
            LinkChoice.Dismissed -> Unit
        }
    }

    private val ContextElement.mediaKind: MediaKind?
        get() = when (type) {
            ContextElement.TYPE_IMAGE -> MediaKind.IMAGE
            ContextElement.TYPE_VIDEO -> MediaKind.VIDEO
            ContextElement.TYPE_AUDIO -> MediaKind.AUDIO
            else -> null
        }
}
