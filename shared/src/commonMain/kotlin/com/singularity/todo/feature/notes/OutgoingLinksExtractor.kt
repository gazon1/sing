package com.singularity.todo.feature.notes

/**
 * Extracts outgoing note/task links from HTML produced by
 * [com.mohamedrejeb.richeditor.model.RichTextState.toHtml].
 *
 * The richeditor library serialises [com.mohamedrejeb.richeditor.model.RichSpanStyle.Link]
 * as `<a href="note://...">` or `<a href="task://...">`.  Walking the internal paragraph
 * tree is not possible because [com.mohamedrejeb.richeditor.model.RichParagraph] is `internal`,
 * so HTML is parsed instead — the format is deterministic and simple enough for regex.
 */

private val SCHEME_FACTORIES: Map<String, (String) -> LinkRef> = mapOf(
    LinkSchemes.NOTE_PREFIX to { id -> LinkRef.Note(id) },
    LinkSchemes.TASK_PREFIX to { id -> LinkRef.Task(id) },
)

private val LINK_REGEX = Regex(
    """<a\s[^>]*href="(${LinkSchemes.NOTE_PREFIX}[^"]+|${LinkSchemes.TASK_PREFIX}[^"]+)"[^>]*>"""
)

fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    for (match in LINK_REGEX.findAll(html)) {
        val url = match.groupValues[1]
        val (prefix, id) = parseLinkUrl(url) ?: continue
        SCHEME_FACTORIES[prefix]?.let { factory -> seen.add(factory(id)) }
    }
    return seen.toList()
}

/** A resolved outgoing link extracted from [RichTextState.toHtml] output. */
sealed interface LinkRef {
    data class Note(val noteId: String) : LinkRef
    data class Task(val taskId: String) : LinkRef
}
