package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.links.InternalLinks

/**
 * Extracts all `note://` and `task://` link IDs from a plain-text string.
 *
 * Unlike the note body's extractor, which reads the editor's markup, this works on
 * raw task description strings where users paste or type links manually. Both read
 * the same vocabulary — [InternalLinks] — so a link means the same thing in either
 * place.
 *
 * The `outgoing_links` column holds a JSON array of URL strings, e.g.
 * `["task://abc123","note://def456"]` — the same shape the notes repository uses.
 */
fun extractOutgoingLinks(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return InternalLinks.findIn(text)
        .map { it.target.url }
        .distinct()
}

/** Serialises a list of link URLs to a JSON array string for DB storage. */
fun List<String>.toLinksJson(): String {
    if (isEmpty()) return "[]"
    // `items` is bound explicitly on purpose. Inside `buildString` the implicit
    // receiver is the StringBuilder, which is a CharSequence, so a bare
    // `forEachIndexed` resolves to CharSequence.forEachIndexed and iterates over
    // the builder's own characters *while appending to it* — an unbounded loop
    // that ends in OutOfMemoryError. Binding the list removes the ambiguity.
    val items = this
    return buildString {
        append('[')
        items.forEachIndexed { i, link ->
            if (i > 0) append(',')
            append('"').append(link).append('"')
        }
        append(']')
    }
}

/** Parses a JSON array string from the `outgoing_links` column back to a list of URLs. */
fun String.parseLinksJson(): List<String> {
    if (isEmpty() || this == "[]") return emptyList()
    return Regex(""""([^"\\]+)"""").findAll(this)
        .map { it.groupValues[1] }
        .toList()
}
