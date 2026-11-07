package wtf.mazy.peel.util

import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.mazy.peel.model.UrlRule

class LinkMatchTest {

    private data class App(val address: String, val entries: List<UrlRule> = emptyList())

    private data class Case(val name: String, val apps: List<App>, val link: String, val expected: App?)

    private fun hostAndPath(url: String): Pair<String, String> {
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return host to path
    }

    private fun matching(url: String, entries: List<UrlRule>): List<UrlRule> {
        val (host, path) = hostAndPath(url)
        return entries.filter { entry ->
            when (entry) {
                is UrlRule.Domain -> host == entry.value || host.endsWith(".${entry.value}")
                is UrlRule.UrlPrefix -> host == entry.host &&
                        (path == entry.path || path.startsWith("${entry.path}/"))
                is UrlRule.UrlRegex -> Regex(entry.value).matches(url)
                is UrlRule.HostRegex -> Regex(entry.value).matches(host)
            }
        }
    }

    private fun match(app: App, url: String): LinkMatch =
        linkMatch(app.address, url) { matching(it, app.entries) }

    private fun assertNoMismatches(mismatches: List<String>) =
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())

    private val google = listOf(
        "https://maps.google.com/", "https://mail.google.com/mail/u/0/",
        "https://drive.google.com/drive/my-drive", "https://docs.google.com/document/",
        "https://calendar.google.com/calendar/r", "https://photos.google.com/", "https://www.google.com/",
    ).map(::App)
    private val googleMain = google.last()
    private val maps = google[0]
    private val mail = google[1]
    private val googleNoMain = google.dropLast(1)
    private val yandex = listOf(
        "https://yandex.ru/maps/", "https://mail.yandex.ru/", "https://music.yandex.ru/",
        "https://market.yandex.ru/", "https://yandex.ru/",
    ).map(::App)
    private val work = listOf(
        "https://acme.atlassian.net/jira/software/projects", "https://acme.atlassian.net/wiki/spaces",
        "https://git.corp.example.com/", "https://ci.corp.example.com/", "https://mail.other.example.com/",
    ).map(::App)
    private val reddit = listOf(
        "https://www.reddit.com/", "https://www.reddit.com/r/androiddev/", "https://www.reddit.com/r/selfhosted/",
    ).map(::App)
    private val youtube = listOf(
        "https://www.youtube.com/", "https://music.youtube.com/", "https://studio.youtube.com/",
    ).map(::App)
    private val outlook = listOf(
        "https://outlook.office.com/mail/", "https://outlook.office.com/calendar/", "https://teams.microsoft.com/",
    ).map(::App)
    private val github = listOf("https://github.com/", "https://github.com/acme/").map(::App)
    private val wikipedia = listOf("https://en.wikipedia.org/", "https://ru.wikipedia.org/").map(::App)
    private val vk = listOf("https://vk.com/", "https://vk.com/im").map(::App)
    private val tenants = listOf("https://example.com/tenant-a/", "https://example.com/tenant-b/").map(::App)
    private val nas = listOf("http://nas.local:5000/", "http://nas.local:8096/").map(::App)
    private val singleName = listOf("http://nas:5000/", "http://nas:8096/", "http://router/").map(::App)
    private val amazon = listOf("https://www.amazon.de/", "https://www.ebay.de/").map(::App)
    private val portal = App("https://portal.example.com/")
    private val tickets = App("https://tickets.example.org/", listOf(UrlRule.UrlPrefix("portal.example.com", "/support")))
    private val ticketsWholeHost = App("https://tickets.example.org/", listOf(UrlRule.Domain("portal.example.com")))
    private val ticketsRegex = App("https://tickets.example.org/", listOf(UrlRule.UrlRegex(".*/support/.*")))
    private val specA = App("https://a.example/", listOf(UrlRule.Domain("shared.example")))
    private val specB = App("https://b.example/", listOf(UrlRule.UrlPrefix("shared.example", "/tenant-b")))
    private val specOwnHost = App("https://shared.example/")

    @Test
    fun picksTheBestMatch() {
        val cases = listOf(
            Case("google search → main", google, "https://www.google.com/search?q=kotlin", googleMain),
            Case("google /maps → Maps", google, "https://www.google.com/maps/place/Eiffel+Tower/@48.85,2.29,17z", maps),
            Case("google /u/0/maps → Maps", google, "https://www.google.com/u/0/maps/dir/a/b", maps),
            Case("maps.app.goo.gl → Maps", google, "https://maps.app.goo.gl/AbCdEf12345", maps),
            Case("goo.gl → main", google, "https://goo.gl/abc", googleMain),
            Case("news.google.com without its app → main", google, "https://news.google.com/x", googleMain),
            Case("mail link → Mail", google, "https://mail.google.com/mail/u/0/#inbox/FMfcgz", mail),
            Case("google /mail → Mail", google, "https://www.google.com/mail", mail),
            Case("other account → Mail", google, "https://mail.google.com/mail/u/1/#inbox", mail),
            Case("docs edit → Docs", google, "https://docs.google.com/document/d/1AbCdEfGh/edit", google[3]),
            Case("sheet on docs host → Docs", google, "https://docs.google.com/spreadsheets/d/1Xy/edit", google[3]),
            Case("drive file → Drive", google, "https://drive.google.com/file/d/1Ab/view", google[2]),
            Case("drive folder → Drive", google, "https://drive.google.com/drive/folders/1Ab", google[2]),
            Case("photos.app.goo.gl → Photos", google, "https://photos.app.goo.gl/XyZ123abc", google[5]),
            Case("google /calendar → Calendar", google, "https://www.google.com/calendar/event?eid=abc", google[4]),
            Case("google.de → main", google, "https://www.google.de/search?q=x", googleMain),
            Case("no main app: search → none", googleNoMain, "https://www.google.com/search?q=x", null),
            Case("no main app: goo.gl → none", googleNoMain, "https://goo.gl/abc", null),
            Case("no main app: maps.app.goo.gl → Maps", googleNoMain, "https://maps.app.goo.gl/abc", maps),
            Case("yandex /maps → Maps", yandex, "https://yandex.ru/maps/213/moscow/?ll=37.6", yandex[0]),
            Case("market product → Market", yandex, "https://market.yandex.ru/product--phone/123456", yandex[3]),
            Case("music track → Music", yandex, "https://music.yandex.ru/album/1/track/2", yandex[2]),
            Case("yandex news → main", yandex, "https://yandex.ru/news/story/abc", yandex[4]),
            Case("taxi.yandex.ru → main", yandex, "https://taxi.yandex.ru/order", yandex[4]),
            Case("jira /browse → none", work, "https://acme.atlassian.net/browse/PROJ-123", null),
            Case("jira board → Jira", work, "https://acme.atlassian.net/jira/software/projects/PROJ/boards/1", work[0]),
            Case("confluence page → Wiki", work, "https://acme.atlassian.net/wiki/spaces/ENG/pages/123/Design", work[1]),
            Case("mail.corp → none between git and ci", work, "https://mail.corp.example.com/owa", null),
            Case("git.corp → git", work, "https://git.corp.example.com/team/repo", work[2]),
            Case("subreddit → its app", reddit, "https://www.reddit.com/r/androiddev/comments/abc/title/", reddit[1]),
            Case("subreddit app root", reddit, "https://www.reddit.com/r/selfhosted/", reddit[2]),
            Case("other subreddit → main", reddit, "https://www.reddit.com/r/kotlin/comments/abc/", reddit[0]),
            Case("redd.it → main", reddit, "https://redd.it/abc123", reddit[0]),
            Case("youtu.be → main", youtube, "https://youtu.be/dQw4w9WgXcQ", youtube[0]),
            Case("youtube watch → main", youtube, "https://www.youtube.com/watch?v=abc", youtube[0]),
            Case("youtube shorts → main", youtube, "https://www.youtube.com/shorts/abc", youtube[0]),
            Case("youtube music → Music", youtube, "https://music.youtube.com/watch?v=abc", youtube[1]),
            Case("outlook mail → Mail", outlook, "https://outlook.office.com/mail/inbox/id/AAQk", outlook[0]),
            Case("outlook calendar → Calendar", outlook, "https://outlook.office.com/calendar/view/week", outlook[1]),
            Case("github org → org app", github, "https://github.com/acme/api/pull/12", github[1]),
            Case("github org issue → org app", github, "https://github.com/acme/web/issues/3", github[1]),
            Case("github other org → main", github, "https://github.com/torvalds/linux", github[0]),
            Case("github settings → main", github, "https://github.com/settings/profile", github[0]),
            Case("wikipedia ru → ru", wikipedia, "https://ru.wikipedia.org/wiki/Android", wikipedia[1]),
            Case("wikipedia en mobile → en", wikipedia, "https://en.m.wikipedia.org/wiki/Android", wikipedia[0]),
            Case("vk messages → im", vk, "https://vk.com/im?sel=123", vk[1]),
            Case("vk wall → main", vk, "https://vk.com/wall-1_2", vk[0]),
            Case("tenant-b → b", tenants, "https://example.com/tenant-b/projects/1", tenants[1]),
            Case("tenant root → none", tenants, "https://example.com/", null),
            Case("nas :8096 → 8096", nas, "http://nas.local:8096/web/x", nas[1]),
            Case("nas :5000 → 5000", nas, "http://nas.local:5000/", nas[0]),
            Case("nas without port → none", nas, "http://nas.local/", null),
            Case("single-name nas:5000", singleName, "http://nas:5000/files", singleName[0]),
            Case("single-name router", singleName, "http://router/admin", singleName[2]),
            Case("amzn.to → amazon.de", amazon, "https://amzn.to/abc", amazon[0]),
            Case("portal /support → Tickets", listOf(portal, tickets), "https://portal.example.com/support/ticket/123", tickets),
            Case("portal /news → Portal", listOf(portal, tickets), "https://portal.example.com/news", portal),
            Case("whole-host entry → none", listOf(portal, ticketsWholeHost), "https://portal.example.com/anything", null),
            Case("regex entry → Tickets", listOf(portal, ticketsRegex), "https://portal.example.com/support/1", ticketsRegex),
            Case("prefix beats domain entry", listOf(specA, specB), "https://shared.example/tenant-b/x", specB),
            Case("prefix beats own host under it", listOf(specOwnHost, specB), "https://shared.example/tenant-b/x", specB),
            Case("own host elsewhere", listOf(specOwnHost, specB), "https://shared.example/other", specOwnHost),
            Case("t.co is not equivalent", listOf(App("https://x.com/"), github[0]), "https://t.co/abc", null),
            Case("unrelated site → none", listOf(App("https://mail.proton.me/"), github[0], App("https://gitlab.com/")), "https://example.com/mail/github", null),
        )
        assertNoMismatches(cases.mapNotNull { case ->
            val actual = case.apps.bestBy { match(it, case.link) }
            "${case.name}: expected ${case.expected?.address}, got ${actual?.address}"
                .takeIf { actual != case.expected }
        })
    }

    @Test
    fun ranksRelationsInOrder() {
        val cases = listOf(
            Triple("https://maps.google.com/", "https://maps.google.com/x", DomainRelation.SAME),
            Triple("https://maps.google.com/", "https://maps.app.goo.gl/x", DomainRelation.EQUIVALENT),
            Triple("https://www.amazon.de/", "https://amzn.to/x", DomainRelation.EQUIVALENT),
            Triple("https://google.com/", "https://google.de/", DomainRelation.SAME_NAME),
            Triple("https://example.com/", "https://other.com/", DomainRelation.SAME_TLD),
            Triple("https://example.com/", "https://other.org/", DomainRelation.NONE),
            Triple("http://nas:5000/", "http://router/", DomainRelation.NONE),
            Triple("https://example.com/", "garbage", DomainRelation.NONE),
        )
        assertNoMismatches(cases.mapNotNull { (app, link, expected) ->
            val actual = match(App(app), link).relation
            "$app ← $link: expected $expected, got $actual".takeIf { actual != expected }
        })
        val equivalentDeep = match(maps, "https://maps.app.goo.gl/x")
        val sameShallow = match(App("https://mail.google.com/"), "https://google.com/x")
        assertTrue("same domain must outrank an equivalent one", sameShallow > equivalentDeep)
    }

    @Test
    fun belonging() {
        val cases = listOf(
            Triple(App("https://maps.google.com/"), "https://maps.app.goo.gl/abc", false),
            Triple(App("https://google.com/"), "https://google.de/", true),
            Triple(App("https://mail.example.com/"), "https://docs.example.com/", true),
            Triple(App("https://example.com/"), "https://other.com/", false),
            Triple(App("http://nas:5000/"), "http://nas:5000/files", true),
            Triple(App("https://a.example/", listOf(UrlRule.Domain("shared.example"))), "https://x.shared.example/", true),
            Triple(App("about:blank", listOf(UrlRule.UrlRegex("https://shared\\.example/.*"))), "https://shared.example/", true),
            Triple(App("https://a.example/", listOf(UrlRule.Domain("lan"))), "http://nas.lan/", true),
            Triple(App("https://a.example/", listOf(UrlRule.Domain("gov.uk"))), "https://service.gov.uk/", true),
            Triple(App("https://amzn.eu/"), "https://amzn.to/x", true),
            Triple(App("https://example.com/"), "data:text/html,https://example.com/", false),
            Triple(App("https://example.com/"), "blob:https://example.com/uuid", false),
            Triple(App("https://a.example/"), "https://shared.example/", false),
        )
        assertNoMismatches(cases.mapNotNull { (app, link, expected) ->
            val actual = match(app, link).belongs
            "${app.address} ← $link: expected $expected, got $actual".takeIf { actual != expected }
        })
    }
}
