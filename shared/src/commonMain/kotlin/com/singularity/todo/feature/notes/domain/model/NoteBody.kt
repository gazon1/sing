package com.singularity.todo.feature.notes.domain.model

import com.singularity.todo.feature.notes.domain.logic.extractOutgoingLinks
import kotlinx.serialization.Serializable

/**
 * Canonical body representation for a [Note][com.singularity.todo.feature.notes.Note].
 *
 * The canonical format is HTML. All other representations are **derived projections**:
 *
 * | Projection | Computed | Stored |
 * |-----------|----------|--------|
 * | `plainText` | on write | as `wordCount`/`charCount` columns |
 * | `markdown` | on demand (lazy) | never |
 * | `outgoingLinks` | on write | as `outgoing_links` column |
 *
 * **This file is free of any UI/library dependency on purpose.** Every projection here
 * is plain string work, which is what makes [NoteBody] unit-testable in `commonTest`
 * with no fakes and no editor. The single place that needs the richeditor library —
 * markdown ⇄ HTML — lives in [NoteBodyMarkdown.kt] and is enforced by
 * `arch/NoteBodyEncapsulationTest`.
 *
 * **Why `fromMarkdown` is a companion method.** A `NoteBodyCodec` abstraction with a
 * single implementation would conflict with two independent constraints:
 * `singularity-todo-rich-editor` removed `MarkdownHtmlPort` ("the library handles this
 * natively"), and `kotlin-idioms` / Rule of Three forbids abstractions with single
 * implementations. The companion keeps the call site natural — `NoteBody.fromMarkdown(md)`
 * reads as a constructor — while the implementation stays in [NoteBodyMarkdown.kt].
 *
 * @param html The canonical HTML representation as produced by
 *             [NoteBodyMarkdown.markdownToHtml].
 */
@Serializable
@JvmInline
value class NoteBody(val html: String) {
    /** True when the body contains no text content (blank or only HTML tags). */
    val isEmpty: Boolean get() = html.isBlank()

    /**
     * Human-readable plain text, suitable for search indexing and previews.
     * Tags are stripped, HTML entities are decoded.
     *
     * Computed with plain string work rather than by asking the editor to render, so
     * that a `NoteCard` row does not allocate a `RichTextState` per frame.
     */
    val plainText: String
        get() = decodeHtmlEntities(stripTags(html))

    /**
     * Readable blocks in document order, for title and preview derivation.
     *
     * Block-level tags and `<br>` become line breaks, so a caller can ask for "the
     * first block" without seeing markup. Shares [plainText]'s tag-stripping and
     * entity-decoding path so the two projections cannot disagree.
     */
    val readableBlocks: List<String>
        get() =
            decodeHtmlEntities(stripTags(html, breakBlocks = true))
                .lines()
                .map { it.replace(WHITESPACE_REGEX, " ").trim() }
                .filter { it.isNotEmpty() }

    /** Word count derived from [plainText]. */
    val wordCount: Int
        get() = plainText.split(WHITESPACE_REGEX).count { it.isNotBlank() }

    /** Character count (HTML string length). */
    val charCount: Int get() = html.length

    /**
     * Outgoing note/task link URLs extracted from the HTML.
     * These are written to the `outgoing_links` column on every save.
     *
     * The `task://<id>` token is **added** to (not replacing) any existing structural
     * `taskId` linkage — see [ADR 2026-10-02-note-entity-dual-task-linkage].
     * Collection properties use union semantics (not LWW) per the sync skill.
     */
    val outgoingLinks: List<String>
        get() = extractOutgoingLinks(html).map { it.url }

    companion object {
        /** Empty body (no content). */
        val Empty = NoteBody("")

        /**
         * Converts markdown from external sources (AI, backup, template) to the canonical [NoteBody].
         *
         * Called only at the boundaries where external input enters the system:
         * - AI tool input, backup restore, template copy
         * NOT on every keystroke — the canonical HTML path is write-only.
         *
         * The conversion itself lives in [NoteBodyMarkdown.kt] so this file stays free
         * of the editor library.
         */
        fun fromMarkdown(markdown: String): NoteBody = NoteBody(markdownToHtml(markdown))
    }
}

private val WHITESPACE_REGEX = Regex("\\s+")
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

/**
 * Strips HTML tags. When [breakBlocks] is set, block-level tags and `<br>` also emit
 * a newline so the text keeps its block structure.
 */
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

// shortcut: common named entities only; add a parser if imported notes use the long tail.
private val NAMED_ENTITIES: Map<String, String> =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "iexcl" to "\u00A1",
        "cent" to "\u00A2",
        "pound" to "\u00A3",
        "curren" to "\u00A4",
        "yen" to "\u00A5",
        "brvbar" to "\u00A6",
        "sect" to "\u00A7",
        "uml" to "\u00A8",
        "copy" to "\u00A9",
        "ordf" to "\u00AA",
        "laquo" to "\u00AB",
        "not" to "\u00AC",
        "shy" to "\u00AD",
        "reg" to "\u00AE",
        "macr" to "\u00AF",
        "deg" to "\u00B0",
        "plusmn" to "\u00B1",
        "sup2" to "\u00B2",
        "sup3" to "\u00B3",
        "acute" to "\u00B4",
        "micro" to "\u00B5",
        "para" to "\u00B6",
        "middot" to "\u00B7",
        "cedil" to "\u00B8",
        "sup1" to "\u00B9",
        "ordm" to "\u00BA",
        "raquo" to "\u00BB",
        "frac14" to "\u00BC",
        "frac12" to "\u00BD",
        "frac34" to "\u00BE",
        "iquest" to "\u00BF",
        "Agrave" to "\u00C0",
        "Aacute" to "\u00C1",
        "Acirc" to "\u00C2",
        "Atilde" to "\u00C3",
        "Auml" to "\u00C4",
        "Aring" to "\u00C5",
        "AElig" to "\u00C6",
        "Ccedil" to "\u00C7",
        "Egrave" to "\u00C8",
        "Eacute" to "\u00C9",
        "Ecirc" to "\u00CA",
        "Euml" to "\u00CB",
        "Igrave" to "\u00CC",
        "Iacute" to "\u00CD",
        "Icirc" to "\u00CE",
        "Iuml" to "\u00CF",
        "ETH" to "\u00D0",
        "Ntilde" to "\u00D1",
        "Ograve" to "\u00D2",
        "Oacute" to "\u00D3",
        "Ocirc" to "\u00D4",
        "Otilde" to "\u00D5",
        "Ouml" to "\u00D6",
        "times" to "\u00D7",
        "Oslash" to "\u00D8",
        "Ugrave" to "\u00D9",
        "Uacute" to "\u00DA",
        "Ucirc" to "\u00DB",
        "Uuml" to "\u00DC",
        "Yacute" to "\u00DD",
        "THORN" to "\u00DE",
        "szlig" to "\u00DF",
        "agrave" to "\u00E0",
        "aacute" to "\u00E1",
        "acirc" to "\u00E2",
        "atilde" to "\u00E3",
        "auml" to "\u00E4",
        "aring" to "\u00E5",
        "aelig" to "\u00E6",
        "ccedil" to "\u00E7",
        "egrave" to "\u00E8",
        "eacute" to "\u00E9",
        "ecirc" to "\u00EA",
        "euml" to "\u00EB",
        "igrave" to "\u00EC",
        "iacute" to "\u00ED",
        "icirc" to "\u00EE",
        "iuml" to "\u00EF",
        "eth" to "\u00F0",
        "ntilde" to "\u00F1",
        "ograve" to "\u00F2",
        "oacute" to "\u00F3",
        "ocirc" to "\u00F4",
        "otilde" to "\u00F5",
        "ouml" to "\u00F6",
        "divide" to "\u00F7",
        "oslash" to "\u00F8",
        "ugrave" to "\u00F9",
        "uacute" to "\u00FA",
        "ucirc" to "\u00FB",
        "uuml" to "\u00FC",
        "yacute" to "\u00FD",
        "thorn" to "\u00FE",
        "yuml" to "\u00FF",
        "OElig" to "\u0152",
        "oelig" to "\u0153",
        "Scaron" to "\u0160",
        "scaron" to "\u0161",
        "Yuml" to "\u0178",
        "fnof" to "\u0192",
        "circ" to "\u02C6",
        "tilde" to "\u02DC",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "zwnj" to "\u200C",
        "zwj" to "\u200D",
        "lrm" to "\u200E",
        "rlm" to "\u200F",
        "ndash" to "\u2013",
        "mdash" to "\u2014",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "sbquo" to "\u201A",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "bdquo" to "\u201E",
        "dagger" to "\u2020",
        "Dagger" to "\u2021",
        "bull" to "\u2022",
        "hellip" to "\u2026",
        "permil" to "\u2030",
        "prime" to "\u2032",
        "Prime" to "\u2033",
        "lsaquo" to "\u2039",
        "rsaquo" to "\u203A",
        "oline" to "\u203E",
        "frasl" to "\u2044",
        "euro" to "\u20AC",
        "trade" to "\u2122",
        "larr" to "\u2190",
        "uarr" to "\u2191",
        "rarr" to "\u2192",
        "darr" to "\u2193",
        "harr" to "\u2194",
        "crarr" to "\u21B5",
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
    return if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val high = ((codePoint - 0x10000) shr 10) + 0xD800
        val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
        charArrayOf(high.toChar(), low.toChar()).concatToString()
    }
}

/**
 * Readable snippet for cards and list rows, truncated to [maxChars].
 *
 * Delegates to [plainText] on purpose: this used to be a `String` extension that ran
 * its own `stripTags` and left entities encoded, so a body containing `Tom &amp; Jerry`
 * rendered as "Tom &amp; Jerry" in a card while `plainText` reported "Tom & Jerry".
 * Two definitions of "readable text" is exactly the kind of drift that survives review,
 * so there is now one — and it is a [NoteBody] method so it cannot be pointed at a raw
 * HTML string by mistake.
 */
fun NoteBody.stripPreview(maxChars: Int = 120): String {
    val text = plainText
    return if (text.length <= maxChars) {
        text
    } else {
        text.take(maxChars).removeSuffix(" ") + "…"
    }
}
