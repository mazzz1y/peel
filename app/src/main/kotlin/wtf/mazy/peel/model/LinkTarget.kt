package wtf.mazy.peel.model

/**
 * What the sheet's target shows for its subject, as the user has edited it. Editing the link
 * drops the element's text, which described the link it replaced.
 */
data class LinkTarget(val link: UnwrappedLink?, val address: String, val text: String?) {

    /** The title line: the text, unless it is missing or only repeats the address. */
    val title: String?
        get() = text?.trim()?.takeIf { it.isNotEmpty() && it != address && it != shownAddress }

    val shownAddress: String get() = displayAddress(address)

    /** The address to share as text; inline data is not an address anyone else can open. */
    val shareable: String? get() = address.takeUnless { it.startsWith("data:", ignoreCase = true) }

    fun edited(link: UnwrappedLink) = LinkTarget(link, link.destination, text = null)

    companion object {
        fun of(subject: LinkSubject) = when (subject) {
            is LinkSubject.Web -> LinkTarget(subject.link, subject.link.destination, subject.text)
            is LinkSubject.Media -> LinkTarget(null, subject.address, subject.text)
        }
    }
}

/**
 * An address as the target shows it: the whole of it, path and query included, without the
 * scheme or a leading `www.`; a data URL by its type.
 */
internal fun displayAddress(address: String): String {
    if (address.startsWith("data:", ignoreCase = true)) {
        return address.substringBefore(',').substring("data:".length).substringBefore(';')
            .ifEmpty { "data:" }
    }
    val withoutScheme = address.substringAfter("://", address)
    return withoutScheme.removePrefix("www.").trimEnd('/').ifEmpty { address }
}
