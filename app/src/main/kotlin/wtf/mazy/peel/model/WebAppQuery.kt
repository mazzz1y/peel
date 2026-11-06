package wtf.mazy.peel.model

fun WebApp.matchesQuery(query: String, groupTitles: Map<String, String>): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
            title.contains(needle, ignoreCase = true) ||
            baseUrl.contains(needle, ignoreCase = true) ||
            groupUuid?.let(groupTitles::get)?.contains(needle, ignoreCase = true) == true
}
