package wtf.mazy.peel.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkAppsTest {

    private val own = "wtf.mazy.peel"
    private val youTube = LinkApp("com.google.android.youtube", "com.google.android.youtube.UrlActivity")
    private val youTubeShorts = LinkApp("com.google.android.youtube", "com.google.android.youtube.ShortsActivity")
    private val music = LinkApp("com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music.Link")
    private val chrome = LinkApp("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher")
    private val peel = LinkApp(own, "wtf.mazy.peel.LinkRouterActivity")
    private val chooser = LinkApp("android", "com.android.internal.app.ResolverActivity")

    private data class Case(
        val name: String,
        val handlers: List<LinkApp>,
        val resolved: LinkApp?,
        val expected: LinkOpeners,
        val browsers: Set<String> = setOf("com.android.chrome", "wtf.mazy.peel"),
    )

    @Test
    fun opensInTheAppsThatOwnTheLinkThenTheDefaultBrowserElseAsksTheSystem() {
        val cases = listOf(
            Case("app, chrome default", listOf(youTube, chrome, peel), chrome, LinkOpeners(listOf(youTube), chrome, false)),
            Case("app, no default", listOf(youTube, chrome, peel), chooser, LinkOpeners(listOf(youTube), null, false)),
            Case("app, peel default", listOf(youTube, chrome, peel), peel, LinkOpeners(listOf(youTube), null, false)),
            Case("only browsers, chrome default", listOf(chrome, peel), chrome, LinkOpeners(emptyList(), chrome, false)),
            Case("only browsers, no default", listOf(chrome, peel), chooser, LinkOpeners(emptyList(), null, true)),
            Case("only browsers, peel default", listOf(chrome, peel), peel, LinkOpeners(emptyList(), null, false)),
            Case("resolves to peel beside a stub browser", listOf(peel), peel, LinkOpeners(emptyList(), null, false)),
            Case("nothing resolves", emptyList(), null, LinkOpeners(emptyList(), null, true)),
            Case("two apps keep their order", listOf(music, chrome, youTube), chrome, LinkOpeners(listOf(music, youTube), chrome, false)),
            Case("one button per package", listOf(youTube, youTubeShorts), chooser, LinkOpeners(listOf(youTube), null, false)),
            Case(
                "peel without a browser role",
                listOf(youTube, peel), chooser, LinkOpeners(listOf(youTube), null, false),
                browsers = setOf(chrome.packageName),
            ),
            Case("no browsers installed", listOf(youTube), null, LinkOpeners(listOf(youTube), null, false), browsers = emptySet()),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, LinkApps.openers(case.handlers, case.resolved, case.browsers, own))
        }
    }
}
