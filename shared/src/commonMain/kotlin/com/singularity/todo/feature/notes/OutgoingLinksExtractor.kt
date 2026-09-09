package com.singularity.todo.feature.notes

/**
 * Extracts outgoing note/task links from HTML produced by [com.mohamedrejeb.richeditor.model.RichTextState.toHtml].
 *
 * The richeditor library serialises [com.mohamedrejeb.richeditor.model.RichSpanStyle.Link]
 * as `<a href="note://...">` or `<a href="task://...">`.  Walking the internal paragraph
 * tree is not possible because [com.mohamedrejeb.richeditor.model.RichParagraph] is `internal`,
 * so HTML is parsed instead — the format is deterministic and simple enough for regex.
 */
internal fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    val regex = Regex("""<a\s[^>]*href="(note://[^"]+)"[^>]*>""")
    for (match in regex.findAll(html)) {
        val url = match.groupValues[1]
        when {
            url.startsWith("note://") -> seen.add(LinkRef.Note(url.removePrefix("note://")))
            url.startsWith("task://") -> seen.add(LinkRef.Task(url.removePrefix("task://")))
        }
    }
    return seen.toList()
}

/** A resolved outgoing link extracted from [RichTextState.toHtml] output. */
sealed interface LinkRef {
    data class Note(val noteId: String) : LinkRef
    data class Task(val taskId: String) : LinkRef
}
