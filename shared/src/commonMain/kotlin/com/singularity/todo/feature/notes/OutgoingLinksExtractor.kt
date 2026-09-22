package com.singularity.todo.feature.notes

/**
 * Extracts outgoing note/task links from HTML produced by [com.mohamedrejeb.richeditor.model.RichTextState.toHtml].
 *
 * The richeditor library serialises [com.mohamedrejeb.richeditor.model.RichSpanStyle.Link]
 * as `<a href="note://...">` or `<a href="task://...">`.  Walking the internal paragraph
 * tree is not possible because [com.mohamedrejeb.richeditor.model.RichParagraph] is `internal`,
 * so HTML is parsed instead — the format is deterministic and simple enough for regex.
 */
fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    val noteRegex = Regex("""<a\s[^>]*href="(note://[^"]+)"[^>]*>""")
    val taskRegex = Regex("""<a\s[^>]*href="(task://[^"]+)"[^>]*>""")
    for (match in noteRegex.findAll(html)) {
        val url = match.groupValues[1]
        seen.add(LinkRef.Note(url.removePrefix("note://")))
    }
    for (match in taskRegex.findAll(html)) {
        val url = match.groupValues[1]
        seen.add(LinkRef.Task(url.removePrefix("task://")))
    }
    return seen.toList()
}

/** A resolved outgoing link extracted from [RichTextState.toHtml] output. */
sealed interface LinkRef {
    data class Note(val noteId: String) : LinkRef
    data class Task(val taskId: String) : LinkRef
}
