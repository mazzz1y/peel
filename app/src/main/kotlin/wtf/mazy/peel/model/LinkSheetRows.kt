package wtf.mazy.peel.model

object LinkSheetRows {

    /** The web apps the sheet may list for [origin], before they are ordered for the link. */
    fun candidates(all: List<WebApp>, origin: LinkOrigin): List<WebApp> = when (origin) {
        is LinkOrigin.Outside -> all.filter(origin.preferred).ifEmpty { all }
        is LinkOrigin.Page -> all.filter { it.uuid != origin.ownerUuid }
        is LinkOrigin.Window.App -> all.filter { it.uuid != origin.app.uuid }
        is LinkOrigin.Window -> all
    }

    private const val RESTING_WEB_APPS = 3

    fun restingCount(rows: List<WebApp>, related: (WebApp) -> Boolean): Int =
        rows.take(RESTING_WEB_APPS).takeWhile(related).size
}
