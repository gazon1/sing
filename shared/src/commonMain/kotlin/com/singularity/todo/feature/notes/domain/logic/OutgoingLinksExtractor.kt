package com.singularity.todo.feature.notes.domain.logic

import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.domain.model.LinkRef

/**
 * Extracts outgoing note/task link URLs from HTML produced by the rich-editor library.
 *
 * The richeditor library serialises link spans as `<a href="note://...">` or
 * `<a href="task://...">`. Walking the internal paragraph tree is not possible because
 * `RichParagraph` is `internal`, so HTML is parsed instead — the format is deterministic
 * and simple enough for regex.
 */

private val SCHEME_FACTORIES: Map<String, (String) -> LinkRef> = mapOf(
    LinkSchemes.NOTE_PREFIX to { id -> LinkRef.Note(id) },
    LinkSchemes.TASK_PREFIX to { id -> LinkRef.Task(id) },
)

private val LINK_REGEX = Regex(
    """<a\s[^>]*href="(${LinkSchemes.NOTE_PREFIX}[^"]+|${LinkSchemes.TASK_PREFIX}[^"]+)"[^>]*>""",
)

/**
 * Extracts unique outgoing [LinkRef]s from [html].
 *
 * Duplicate URLs are deduplicated — if the editor links to the same note twice,
 * the result contains each ID only once.
 */
fun extractOutgoingLinks(html: String): List<LinkRef> {
    val seen = mutableSetOf<LinkRef>()
    for (match in LINK_REGEX.findAll(html)) {
        val url = match.groupValues[1]
        val (prefix, id) = parseLinkUrl(url) ?: continue
        SCHEME_FACTORIES[prefix]?.let { factory -> seen.add(factory(id)) }
    }
    return seen.toList()
}

/**
 * Extracts (scheme-prefix, entity-id) from a `kind://id` URL, or null if the URL
 * uses an unknown scheme or is malformed.
 */
private fun parseLinkUrl(url: String): Pair<String, String>? {
    val prefix = when {
        url.startsWith(LinkSchemes.NOTE_PREFIX) -> LinkSchemes.NOTE_PREFIX
        url.startsWith(LinkSchemes.TASK_PREFIX) -> LinkSchemes.TASK_PREFIX
        else -> return null
    }
    val id = url.removePrefix(prefix)
    return if (id.isEmpty()) null else prefix to id
}
