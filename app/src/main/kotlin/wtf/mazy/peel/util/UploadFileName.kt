package wtf.mazy.peel.util

private const val MAX_NAME_BYTES = 255
private const val FALLBACK_STEM = "file"
private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")

/** The name a picked file is stored under, which is the name the page receives. */
fun uploadFileName(displayName: String?, typeExtension: String?): String {
    val name = displayName
        ?.substringAfterLast('/')
        ?.replace(CONTROL_CHARACTERS, "_")
        ?.trim()
        ?.takeUnless { it.isEmpty() || it == "." || it == ".." }
        ?: return typeExtension?.let { "$FALLBACK_STEM.$it" } ?: FALLBACK_STEM
    return name.limitedTo(MAX_NAME_BYTES)
}

private fun String.limitedTo(maxBytes: Int): String {
    if (utf8Size() <= maxBytes) return this
    val dot = lastIndexOf('.')
    val extension = if (dot > 0) substring(dot) else ""
    val budget = maxBytes - extension.utf8Size()
    if (budget <= 0) return truncatedTo(maxBytes)
    return substring(0, length - extension.length).truncatedTo(budget) + extension
}

private fun String.truncatedTo(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        bytes += String(Character.toChars(codePoint)).utf8Size()
        if (bytes > maxBytes) break
        end += Character.charCount(codePoint)
    }
    return substring(0, end)
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
