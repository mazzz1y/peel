package wtf.mazy.peel.model

import wtf.mazy.peel.util.WebOrigin

/** What a long press on a page element does. */
sealed interface LongPress {
    data class Sheet(val subject: LinkSubject) : LongPress
    data class Copy(val text: String) : LongPress

    companion object {
        fun of(
            linkUri: String?,
            linkText: String?,
            altText: String?,
            title: String?,
            media: MediaKind?,
            srcUri: String?,
        ): LongPress? {
            val text = listOf(linkText, altText, title).firstNotNullOfOrNull { it?.trim()?.ifEmpty { null } }
            val link = linkUri?.takeUnless { it.startsWith("javascript:", ignoreCase = true) }
            val image = srcUri?.takeIf { media == MediaKind.IMAGE }
            return when {
                link != null && WebOrigin.of(link) != null ->
                    Sheet(LinkSubject.Web(Redirectors.unwrap(link), text, image))

                link != null -> Copy(LongPressCopy.of(link))
                media != null && srcUri != null -> Sheet(LinkSubject.Media(media, srcUri, text))
                else -> null
            }
        }
    }
}
