package wtf.mazy.peel.model

/**
 * Domains that stand for one other site's domain. Domains that also wrap links to other sites,
 * such as `t.co` or `lnkd.in`, are left out, and so are those whose name already matches the
 * site's (`apple.co`).
 */
object EquivalentDomains {

    private val sites = mapOf(
        "goo.gl" to "google.com",
        "youtu.be" to "youtube.com",
        "redd.it" to "reddit.com",
        "fb.me" to "facebook.com",
        "instagr.am" to "instagram.com",
        "amzn.to" to "amazon.com",
        "a.co" to "amazon.com",
        "amzn.eu" to "amazon.com",
        "wa.me" to "whatsapp.com",
        "t.me" to "telegram.org",
        "spoti.fi" to "spotify.com",
        "pin.it" to "pinterest.com",
    )

    fun siteOf(domain: String): String? = sites[domain]
}
