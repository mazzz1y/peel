package wtf.mazy.peel.model

data class LinkApp(val packageName: String, val activityName: String)

data class LinkOpeners(val apps: List<LinkApp>, val browser: LinkApp?, val asksSystem: Boolean)

object LinkApps {

    /**
     * [resolved] is what Android opens an address no app claims in. When that is Peel, as the
     * default or as the only real browser beside a stub, "Open in system" would bring the link
     * straight back.
     */
    fun openers(handlers: List<LinkApp>, resolved: LinkApp?, browsers: Set<String>, ownPackage: String): LinkOpeners {
        val owning = handlers
            .filterNot { it.packageName in browsers || it.packageName == ownPackage }
            .distinctBy { it.packageName }
        val browser = resolved?.takeIf { it.packageName in browsers && it.packageName != ownPackage }
        val backToPeel = resolved?.packageName == ownPackage
        return LinkOpeners(owning, browser, asksSystem = owning.isEmpty() && browser == null && !backToPeel)
    }
}
