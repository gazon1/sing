package com.singularity.todo.feature.notes

/** Single source of truth for internal note/task link URL schemes. */
object LinkSchemes {
    const val NOTE = "note"
    const val TASK = "task"

    const val NOTE_PREFIX = "$NOTE://"
    const val TASK_PREFIX = "$TASK://"
}

/** Maps a [LinkKind] to its canonical URL prefix. */
val LinkKind.prefix: String
    get() = when (this) {
        LinkKind.Note -> LinkSchemes.NOTE_PREFIX
        LinkKind.Task -> LinkSchemes.TASK_PREFIX
    }

/** Builds a `kind://id` URL from a [LinkKind] and entity id. */
fun LinkKind.urlFor(id: String): String = "${prefix}$id"

/**
 * Extracts (kind-prefix, entity-id) from a `kind://id` URL, or null if the URL
 * uses an unknown scheme or is malformed.
 */
fun parseLinkUrl(url: String): Pair<String, String>? {
    val prefix = when {
        url.startsWith(LinkSchemes.NOTE_PREFIX) -> LinkSchemes.NOTE_PREFIX
        url.startsWith(LinkSchemes.TASK_PREFIX) -> LinkSchemes.TASK_PREFIX
        else -> return null
    }
    val id = url.removePrefix(prefix)
    return if (id.isEmpty()) null else prefix to id
}
