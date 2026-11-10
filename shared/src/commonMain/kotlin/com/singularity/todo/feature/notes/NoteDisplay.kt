package com.singularity.todo.feature.notes

import com.singularity.todo.feature.notes.domain.NoteContentMapper

private const val TITLE_MAX_CHARS = 80
private const val SNIPPET_MAX_CHARS = 120

/**
 * Presentation-only title and snippet derivation for notes.
 *
 * Derived text is never persisted to [Note.title]. Explicit titles always win.
 */
fun Note.displayTitle(untitled: String = "Untitled"): String {
    val explicit = title.trim()
    if (explicit.isNotEmpty()) return title
    if (isFolder) return untitled
    val firstLine = readableBodyLines().firstOrNull() ?: return untitled
    return collapseAndTruncate(firstLine, TITLE_MAX_CHARS)
}

/**
 * Readable body snippet for list/preview surfaces.
 *
 * When the title is derived from the body, the first readable line that produced
 * it is omitted to avoid repetition.
 */
fun Note.displaySnippet(maxChars: Int = SNIPPET_MAX_CHARS): String {
    val lines = readableBodyLines()
    val source = if (title.trim().isEmpty() && !isFolder) lines.drop(1) else lines
    return collapseAndTruncate(source.joinToString(" "), maxChars)
}

private fun Note.readableBodyLines(): List<String> {
    val html = bodyHtml?.takeIf { it.isNotBlank() } ?: markdownAsHtml()
    return html?.let(::htmlToReadableLines).orEmpty()
}

private fun Note.markdownAsHtml(): String? =
    bodyMarkdown
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { NoteContentMapper.toHtml(it) }.getOrNull() }

// Block boundaries become line breaks so "first readable line" means "first block".
private fun htmlToReadableLines(html: String): List<String> =
    html
        .replace(BR_REGEX, "\n")
        .replace(BLOCK_CLOSE_REGEX, "\n")
        .replace(LIST_ITEM_OPEN_REGEX, "\n")
        .replace(BLOCK_OPEN_REGEX, "\n")
        .replace(TAG_REGEX, "")
        .let(::decodeHtmlEntities)
        .lines()
        .map { it.replace(WHITESPACE_REGEX, " ").trim() }
        .filter { it.isNotEmpty() }

// shortcut: regex HTML stripping; upgrade to an HTML parser if attributes with '>' or scripts appear.
private val BR_REGEX = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)
private val BLOCK_CLOSE_REGEX =
    Regex(
        """</\s*(p|div|li|h[1-6]|blockquote|pre|section|article|tr|ul|ol)\s*>""",
        RegexOption.IGNORE_CASE,
    )
private val LIST_ITEM_OPEN_REGEX = Regex("""<\s*li\b[^>]*>""", RegexOption.IGNORE_CASE)
private val BLOCK_OPEN_REGEX =
    Regex("""<\s*(p|div|h[1-6]|blockquote|pre|section|article)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val TAG_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")

private fun decodeHtmlEntities(text: String): String =
    buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val semicolon = if (text[i] == '&') text.indexOf(';', i + 1) else -1
            val decoded =
                if (semicolon != -1 && semicolon - i <= 32) {
                    decodeEntityBody(text.substring(i + 1, semicolon))
                } else {
                    null
                }
            if (decoded != null) {
                append(decoded)
                i = semicolon + 1
            } else {
                append(text[i])
                i++
            }
        }
    }

private fun decodeEntityBody(entity: String): String? =
    when (entity) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos", "#39", "#x27", "#X27" -> "'"
        "nbsp" -> " "
        "mdash" -> "\u2014"
        "ndash" -> "\u2013"
        "hellip" -> "\u2026"
        "ldquo" -> "\u201C"
        "rdquo" -> "\u201D"
        "lsquo" -> "\u2018"
        "rsquo" -> "\u2019"
        "bull" -> "\u2022"
        "middot" -> "\u00B7"
        "copy" -> "\u00A9"
        "reg" -> "\u00AE"
        "trade" -> "\u2122"
        else -> decodeNumericEntity(entity)
    }

private fun decodeNumericEntity(entity: String): String? {
    if (entity.isEmpty() || entity[0] != '#') return null
    val codePoint =
        try {
            if (entity.length >= 2 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toInt(16)
            } else {
                entity.substring(1).toInt(10)
            }
        } catch (_: Exception) {
            return null
        }
    if (codePoint !in 0..0x10FFFF) return null
    return if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val high = ((codePoint - 0x10000) shr 10) + 0xD800
        val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
        charArrayOf(high.toChar(), low.toChar()).concatToString()
    }
}

private fun collapseAndTruncate(
    text: String,
    maxChars: Int,
): String {
    val collapsed = text.replace(WHITESPACE_REGEX, " ").trim()
    if (maxChars <= 0 || collapsed.isEmpty()) return ""
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars).trimEnd() + "…"
}
