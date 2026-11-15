package com.singularity.todo.core.text

/**
 * The one conversion boundary between a task description's canonical HTML and its
 * plain-text projection.
 *
 * ## Why the task has two fields
 *
 * `Task.description` is stored as plain text, and it must stay plain text: the
 * full-text index (`tasks_fts`) is an external-content FTS4 table over that
 * column, and Room's triggers copy the column verbatim into the index. Storing
 * HTML there would make every tag name a searchable word. AI prompts, calendar
 * bodies and the `has:description` filter read the same column, and they want
 * the text a person wrote, not markup.
 *
 * `Task.descriptionHtml` holds the editor's HTML. It is what the editor loads and
 * what the read-only renderer renders. The two fields are kept in step here, so
 * no caller has to decide what a description means.
 *
 * ## Legacy content is literal
 *
 * A description written before this field existed has `descriptionHtml == null`.
 * [canonical] turns that plain text into HTML by escaping it, so a legacy
 * description that happens to contain `<b>` is shown as the characters the user
 * typed, not interpreted as markup.
 *
 * The same rule applies when the two fields disagree: a writer that only knows
 * about plain text (an AI tool, a calendar import, an old client) changes
 * `description` and leaves the old HTML behind. Plain text wins in that case,
 * because the HTML no longer describes it.
 */
object TaskDescriptionHtml {
    /**
     * The HTML the editor and the read-only renderer show.
     *
     * @param html the rich representation, if one was ever stored.
     * @param plain the plain-text projection stored in `Task.description`.
     */
    fun canonical(
        html: String?,
        plain: String?,
    ): String = storedHtml(html, plain) ?: plainTextToHtml(plain.orEmpty())

    /**
     * The rich representation to keep, or null when [html] does not describe
     * [plain].
     *
     * Null is not "no description": it means the HTML cannot be trusted as the
     * description's rendering, and the plain text is the truth. A write that
     * changes only [plain] therefore drops the stale HTML instead of letting the
     * editor show text the task no longer holds.
     */
    fun storedHtml(
        html: String?,
        plain: String?,
    ): String? {
        if (html.isNullOrBlank()) return null
        val text = htmlToPlainText(html)
        return when {
            text.isEmpty() -> null
            plain == null -> html
            text == plain -> html
            else -> null
        }
    }

    /**
     * The plain projection to keep.
     *
     * When the row holds only HTML — a document from a peer that sent the rich
     * field without the projection — the projection is derived rather than
     * dropped.
     */
    fun storedPlain(
        html: String?,
        plain: String?,
    ): String? =
        when {
            html.isNullOrBlank() -> plain?.ifBlank { null }
            plain == null -> htmlToPlainText(html).ifBlank { null }
            else -> plain.ifBlank { null }
        }

    /** The plain-text projection of [html]; empty for null. */
    fun plainText(html: String?): String = htmlToPlainText(html.orEmpty())

    /**
     * Converts stored plain text to the editor's HTML, escaping it so it is read
     * literally. Line breaks become `<br>`.
     */
    fun plainTextToHtml(text: String): String {
        if (text.isEmpty()) return ""
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').joinToString("<br>") { escapeHtml(it) }
    }

    /**
     * Human-readable text for search, previews and prompts.
     *
     * Block-level tags and `<br>` emit a line break, entities are decoded, and
     * runs of horizontal whitespace collapse. This is deterministic, so the value
     * written to `Task.description` is exactly the value [storedHtml] compares
     * against on the next write.
     */
    fun htmlToPlainText(html: String): String {
        if (html.isEmpty()) return ""
        val decoded = decodeHtmlEntities(stripTags(html, breakBlocks = true))
        return decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { line -> line.replace(HORIZONTAL_WHITESPACE, " ").trim() }
            .replace(EXCESS_NEWLINES, "\n")
            .trim('\n')
    }
}

private val HORIZONTAL_WHITESPACE = Regex("[\\t\\u000B\\u000C \\u00A0]+")
private val EXCESS_NEWLINES = Regex("\n{2,}")
private val COMMENT_REGEX = Regex("(?is)<!--.*?-->")
private val RAW_TEXT_ELEMENT_REGEX = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")

private val BLOCK_TAGS =
    setOf(
        "address",
        "article",
        "aside",
        "blockquote",
        "br",
        "div",
        "footer",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "header",
        "hr",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "pre",
        "section",
        "table",
        "td",
        "th",
        "tr",
        "ul",
    )

/** Escapes the three characters that would otherwise be read as markup. */
private fun escapeHtml(text: String): String =
    buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

/** Strips HTML tags. When [breakBlocks] is set, block-level tags and `<br>` emit a newline. */
private fun stripTags(
    html: String,
    breakBlocks: Boolean = false,
): String {
    val cleaned = html.replace(COMMENT_REGEX, "").replace(RAW_TEXT_ELEMENT_REGEX, "")
    val out = StringBuilder(cleaned.length)
    var i = 0
    while (i < cleaned.length) {
        val lt = cleaned.indexOf('<', i)
        val textEnd = if (lt == -1) cleaned.length else lt
        out.append(cleaned, i, textEnd)
        if (lt == -1) {
            i = cleaned.length
        } else {
            val gt = tagEnd(cleaned, lt)
            if (gt == -1) {
                out.append(cleaned, lt, cleaned.length)
                i = cleaned.length
            } else {
                if (breakBlocks) appendBlockBreak(cleaned, lt, gt, out)
                i = gt + 1
            }
        }
    }
    return out.toString()
}

/** Index of the `>` closing the tag at [lt], skipping quoted attribute values, or -1. */
private fun tagEnd(
    html: String,
    lt: Int,
): Int {
    var quote = '\u0000'
    var i = lt + 1
    while (i < html.length) {
        val c = html[i]
        when {
            quote != '\u0000' -> if (c == quote) quote = '\u0000'
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i
        }
        i++
    }
    return -1
}

private fun appendBlockBreak(
    html: String,
    lt: Int,
    gt: Int,
    out: StringBuilder,
) {
    var start = lt + 1
    if (start < gt && html[start] == '/') start++
    val nameStart = start
    while (start < gt && html[start].isLetterOrDigit()) start++
    if (start > nameStart && html.substring(nameStart, start).lowercase() in BLOCK_TAGS) out.append('\n')
}

private fun decodeHtmlEntities(html: String): String =
    buildString(html.length) {
        var i = 0
        while (i < html.length) {
            val c = html[i]
            val semicolon = if (c == '&') html.indexOf(';', i + 1) else -1
            val decoded =
                if (semicolon != -1 && semicolon - i <= 32) {
                    decodeHtmlEntityToken(html.substring(i + 1, semicolon))
                } else {
                    null
                }
            if (decoded != null) {
                append(decoded)
                i = semicolon + 1
            } else {
                append(c)
                i++
            }
        }
    }

private fun decodeHtmlEntityToken(entity: String): String? = NAMED_ENTITIES[entity] ?: decodeNumericEntity(entity)

// shortcut: the handful of named entities the editor emits; add the long tail if imported content needs it.
private val NAMED_ENTITIES: Map<String, String> =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "\u2013",
        "mdash" to "\u2014",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "hellip" to "\u2026",
        "rarr" to "\u2192",
        "larr" to "\u2190",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "euro" to "\u20AC",
        "pound" to "\u00A3",
        "yen" to "\u00A5",
        "cent" to "\u00A2",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB",
        "bull" to "\u2022",
        "prime" to "\u2032",
        "minus" to "\u2212",
    )

private fun decodeNumericEntity(entity: String): String? {
    if (entity.isEmpty() || entity[0] != '#') return null
    val codePoint =
        try {
            if (entity.length >= 2 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toInt(16)
            } else {
                entity.substring(1).toInt(10)
            }
        } catch (_: NumberFormatException) {
            return null
        }
    if (codePoint !in 0..0x10FFFF) return null
    // A lone surrogate is not a character: it cannot be encoded, and letting it
    // through would put invalid text into the plain projection. The entity stays
    // literal instead.
    if (codePoint in 0xD800..0xDFFF) return null
    return if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val high = ((codePoint - 0x10000) shr 10) + 0xD800
        val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
        charArrayOf(high.toChar(), low.toChar()).concatToString()
    }
}
