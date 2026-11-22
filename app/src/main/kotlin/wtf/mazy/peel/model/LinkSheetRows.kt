package wtf.mazy.peel.model

sealed interface LinkSheetRow {
    data class CurrentWindow(val window: LinkOrigin.Window) : LinkSheetRow
    data class App(val app: WebApp) : LinkSheetRow
}

object LinkSheetRows {

    /** The web apps the sheet may list for [origin], before they are ordered for the link. */
    fun candidates(all: List<WebApp>, origin: LinkOrigin): List<WebApp> = when (origin) {
        is LinkOrigin.Outside -> all.filter(origin.preferred).ifEmpty { all }
        is LinkOrigin.Page -> all.filter { it.uuid != origin.ownerUuid }
        is LinkOrigin.Window.App -> all.filter { it.uuid != origin.app.uuid }
        is LinkOrigin.Window -> all
    }

    private const val RESTING_WEB_APPS = 3

    fun restingCount(rows: List<LinkSheetRow>, related: (WebApp) -> Boolean): Int {
        val window = rows.count { it is LinkSheetRow.CurrentWindow }
        val apps = rows.drop(window).take(RESTING_WEB_APPS).takeWhile { it is LinkSheetRow.App && related(it.app) }
        return window + apps.size
    }

    /** Whether, before any search, there is somewhere to open the link. */
    fun hasRows(candidates: List<WebApp>, origin: LinkOrigin): Boolean =
        origin is LinkOrigin.Window || candidates.isNotEmpty()

    /** The rows for [query]: the current window first, as its web app's own row would be kept. */
    fun rows(
        ordered: List<WebApp>,
        origin: LinkOrigin,
        query: String,
        groupTitles: Map<String, String>,
    ): List<LinkSheetRow> {
        val apps = ordered.searched(query, groupTitles).map(LinkSheetRow::App)
        val window = origin as? LinkOrigin.Window ?: return apps
        val kept = when (window) {
            is LinkOrigin.Window.App -> listOf(window.app).searched(query, groupTitles).isNotEmpty()
            else -> query.isBlank()
        }
        return if (kept) listOf(LinkSheetRow.CurrentWindow(window)) + apps else apps
    }
}
