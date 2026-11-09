package com.singularity.todo.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The legacy app stored a note body as Quill Delta JSON, and a project or task
 * "note" is an id that points at one of those bodies.
 *
 * `[{"insert":"text\n"},{"insert":" bold","attributes":{"bold":true}}]` is a
 * document, not words a person can read. This turns one into HTML for the note
 * editor and into plain text for the description fields.
 *
 * The input is one of three shapes, told apart by the first character: a Delta
 * array (`[`), HTML (`<`), or plain text. Anything that looks like a Delta
 * array but carries no `insert` is read as plain text rather than dropped.
 */
internal object LegacyText {
    /** The body as HTML, for [com.singularity.todo.core.backup.NoteDto.bodyHtml]. */
    fun toHtml(raw: String): String {
        val blocks = parseDelta(raw) ?: return plainOrHtmlToHtml(raw)
        return renderHtml(blocks)
    }

    /** The body as words, for the description fields and the word count. */
    fun toPlainText(raw: String): String {
        val blocks = parseDelta(raw) ?: return plainOrHtmlToText(raw)
        return renderPlainText(blocks)
    }
}

// ── the Delta document ────────────────────────────────────────────────────────

private sealed interface Block {
    /** One line: its inline spans and the attributes of the newline that ended it. */
    data class Line(val spans: List<Span>, val attrs: JsonObject?) : Block

    data object Divider : Block
}

private data class Span(val text: String, val attrs: JsonObject?)

/** Turns the ops of a Delta array into lines, one line per newline op. */
private class DeltaLines {
    private val blocks = mutableListOf<Block>()
    private var spans = mutableListOf<Span>()

    fun read(ops: JsonArray): List<Block> {
        for (element in ops) {
            val op = element as? JsonObject ?: continue
            readOp(op)
        }
        if (spans.isNotEmpty()) blocks += Block.Line(spans, null)
        // Quill writes a newline after an embed; it is the embed's own, not a blank line.
        return blocks.filterIndexed { index, block ->
            !(block is Block.Line && block.spans.isEmpty() && index > 0 && blocks[index - 1] is Block.Divider)
        }
    }

    private fun readOp(op: JsonObject) {
        val attrs = op["attributes"] as? JsonObject
        when (val insert = op["insert"]) {
            is JsonPrimitive -> {
                if (insert.isString) readText(insert.content, attrs)
            }

            is JsonObject -> {
                if (insert.containsKey("divider")) blocks += Block.Divider
            }

            else -> {
                Unit
            }
        }
    }

    private fun readText(
        text: String,
        attrs: JsonObject?,
    ) {
        var start = 0
        text.forEachIndexed { index, char ->
            if (char != '\n') return@forEachIndexed
            if (index > start) spans += Span(text.substring(start, index), attrs)
            blocks += Block.Line(spans, attrs)
            spans = mutableListOf()
            start = index + 1
        }
        if (start < text.length) spans += Span(text.substring(start), attrs)
    }
}

private fun parseDelta(raw: String): List<Block>? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("[")) return null
    val ops = runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull() ?: return null
    if (ops.none { it is JsonObject && it.containsKey("insert") }) return null
    return DeltaLines().read(ops)
}

// ── HTML ──────────────────────────────────────────────────────────────────────

private fun renderHtml(blocks: List<Block>): String {
    val out = StringBuilder()
    var list: ListHtml? = null

    fun flushList() {
        list?.let { out.append(it.finish()) }
        list = null
    }
    for (block in blocks) {
        when (block) {
            is Block.Divider -> {
                flushList()
                out.append("<hr>")
            }

            is Block.Line -> {
                val listType = block.attrs?.string("list")
                if (listType == null) {
                    flushList()
                    out.append(blockHtml(block))
                } else {
                    val tag = if (listType == "ordered") "ol" else "ul"
                    val marker =
                        when (listType) {
                            "checked" -> {
                                "☑ "
                            }

                            "unchecked" -> {
                                "☐ "
                            }

                            else -> {
                                ""
                            }
                        }
                    val level = block.attrs?.int("indent") ?: 0
                    val renderer = list ?: ListHtml().also { list = it }
                    renderer.item(level, tag, marker + inlineHtml(block.spans))
                }
            }
        }
    }
    flushList()
    return out.toString()
}

private fun blockHtml(line: Block.Line): String {
    val inner = inlineHtml(line.spans).ifEmpty { "<br>" }
    if (line.attrs?.boolean("code-block") == true) {
        return "<pre><code>${escapeHtml(line.spans.joinToString("") { it.text })}</code></pre>"
    }
    // The editor knows h1..h3; the legacy file goes up to h5.
    val header = line.attrs?.int("header")?.coerceIn(1, 3)
    if (header != null) return "<h$header>$inner</h$header>"
    if (line.attrs?.boolean("blockquote") == true) return "<blockquote>$inner</blockquote>"
    return "<p>$inner</p>"
}

private fun inlineHtml(spans: List<Span>): String = spans.joinToString("") { spanHtml(it) }

private fun spanHtml(span: Span): String {
    if (span.text.isEmpty()) return ""
    var html = escapeHtml(span.text)
    val attrs = span.attrs ?: return html
    if (attrs.boolean("code") == true) html = "<code>$html</code>"
    if (attrs.boolean("bold") == true) html = "<strong>$html</strong>"
    if (attrs.boolean("italic") == true) html = "<em>$html</em>"
    if (attrs.boolean("underline") == true) html = "<u>$html</u>"
    if (attrs.boolean("strike") == true) html = "<s>$html</s>"
    val link = attrs.string("link")
    if (link != null && isSafeHref(link)) html = """<a href="${escapeHtml(link)}">$html</a>"""
    return html
}

/**
 * A nested list. `indent` is the level the legacy line claimed; a line may
 * not open a level deeper than the one after the current one, because a
 * `ul` inside a `ul` with no `li` between them is not a document either.
 */
private class ListHtml {
    private val out = StringBuilder()
    private val tags = mutableListOf<String>()
    private var itemOpen = false

    fun item(
        level: Int,
        tag: String,
        inner: String,
    ) {
        val target = level.coerceIn(0, tags.size)
        while (tags.size > target + 1) closeLevel()
        if (tags.size < target + 1) {
            repeat(target + 1 - tags.size) {
                out.append("<$tag>")
                tags.add(tag)
            }
        } else {
            if (itemOpen) out.append("</li>")
            if (tags.last() != tag) {
                out.append("</").append(tags.removeAt(tags.lastIndex)).append(">")
                out.append("<$tag>")
                tags.add(tag)
            }
        }
        out.append("<li>").append(inner)
        itemOpen = true
    }

    private fun closeLevel() {
        if (itemOpen) out.append("</li>")
        out.append("</").append(tags.removeAt(tags.lastIndex)).append(">")
        itemOpen = tags.isNotEmpty()
    }

    fun finish(): String {
        while (tags.isNotEmpty()) closeLevel()
        return out.toString()
    }
}

// ── plain text ────────────────────────────────────────────────────────────────

private fun renderPlainText(blocks: List<Block>): String {
    val lines = mutableListOf<String>()
    var ordered = 0
    for (block in blocks) {
        when (block) {
            is Block.Divider -> {
                ordered = 0
                lines += "———"
            }

            is Block.Line -> {
                val text = block.spans.joinToString("") { it.text }
                val listType = block.attrs?.string("list")
                if (listType == null) {
                    ordered = 0
                    val quote = if (block.attrs?.boolean("blockquote") == true) "> " else ""
                    lines += quote + text
                } else {
                    val indent = "    ".repeat((block.attrs?.int("indent") ?: 0).coerceIn(0, 4))
                    val marker =
                        when (listType) {
                            "ordered" -> {
                                "${++ordered}. "
                            }

                            "checked" -> {
                                "☑ "
                            }

                            "unchecked" -> {
                                "☐ "
                            }

                            else -> {
                                ordered = 0
                                "• "
                            }
                        }
                    lines += indent + marker + text
                }
            }
        }
    }
    return lines.joinToString("\n")
}

// ── the shapes that are not Delta ─────────────────────────────────────────────

/** A legacy string can carry markup in the middle of a sentence, not only at the start. */
private val htmlTag = Regex("</?[a-zA-Z][^>]*>")

private fun plainOrHtmlToHtml(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("<") || htmlTag.containsMatchIn(trimmed)) return trimmed
    return unescapeEntities(trimmed)
        .split("\n")
        .joinToString("") { line -> "<p>${if (line.isEmpty()) "<br>" else escapeHtml(line)}</p>" }
}

private fun plainOrHtmlToText(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("<") && !htmlTag.containsMatchIn(trimmed)) return unescapeEntities(trimmed)
    val withoutTags =
        trimmed
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
    return unescapeEntities(withoutTags).trim()
}

/** The text a legacy string means, not the entities it spells it with. */
private fun unescapeEntities(text: String): String =
    text
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

private fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/** Only the schemes a note can carry; a legacy link is never script. */
private fun isSafeHref(href: String): Boolean =
    href.startsWith("http://") ||
        href.startsWith("https://") ||
        href.startsWith("mailto:") ||
        href.startsWith("note://") ||
        href.startsWith("task://")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
