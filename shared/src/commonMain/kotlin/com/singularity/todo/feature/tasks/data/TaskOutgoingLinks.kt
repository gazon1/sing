package com.singularity.todo.feature.tasks.data

import com.singularity.todo.feature.notes.LinkSchemes

/**
 * JSON serialization helpers for the `outgoing_links` column on [TaskEntity].
 *
 * Mirrors the pattern used in [com.singularity.todo.feature.notes.NotesRepository]
 * for `NoteEntity.outgoingLinks`.
 *
 * Links are stored as a JSON array of URL strings, e.g.:
 * `["task://abc123","note://def456"]`
 *
 * Extraction from plain-text descriptions uses [extractOutgoingLinks].
 */
private val LINK_REGEX = Regex(
    """${LinkSchemes.NOTE_PREFIX}[a-zA-Z0-9_-]+|${LinkSchemes.TASK_PREFIX}[a-zA-Z0-9_-]+""",
)

/**
 * Extracts all `note://` and `task://` link IDs from a plain-text string.
 *
 * Unlike [com.singularity.todo.feature.notes.extractOutgoingLinks] which parses
 * HTML from the rich-editor, this works on raw task description strings
 * where users paste or type links manually.
 */
fun extractOutgoingLinks(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return LINK_REGEX.findAll(text)
        .map { it.value }
        .distinct()
        .toList()
}

/** Serialises a list of link URLs to a JSON array string for DB storage. */
fun List<String>.toLinksJson(): String = when {
    isEmpty() -> "[]"

    else -> buildString {
        append('[')
        forEachIndexed { i, link ->
            if (i > 0) append(',')
            append('"')
            append(link)
            append('"')
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
