package wtf.mazy.peel.util

/** Admits an address from outside Peel only when it is a web address with a host. */
object IncomingUrl {
    fun accept(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return url.takeIf { WebOrigin.of(it) != null }
    }
}
