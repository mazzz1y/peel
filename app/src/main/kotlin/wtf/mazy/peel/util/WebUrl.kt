package wtf.mazy.peel.util

object WebUrl {

    private val LINK = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    private val CLOSING = mapOf(')' to '(', ']' to '[', '}' to '{')
    private const val PUNCTUATION = ".,;:!?"

    fun accept(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return url.takeIf { WebOrigin.of(it) != null }
    }

    fun find(text: String): String? = LINK.find(text)?.value?.let(::trimSentence)?.let(::accept)

    // Sentence punctuation after a link isn't part of it; a closing bracket is only when the link
    // opened it, as in `Kotlin_(language)`.
    private fun trimSentence(link: String): String {
        var end = link.length
        while (end > 0) {
            val last = link[end - 1]
            val opening = CLOSING[last]
            val unbalanced = opening != null &&
                    link.substring(0, end).count { it == opening } < link.substring(0, end).count { it == last }
            if (last in PUNCTUATION || unbalanced) end-- else break
        }
        return link.substring(0, end)
    }
}
