package wtf.mazy.peel.util

private val UUID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

fun String.isCanonicalUuid(): Boolean = UUID_REGEX.matches(this)
