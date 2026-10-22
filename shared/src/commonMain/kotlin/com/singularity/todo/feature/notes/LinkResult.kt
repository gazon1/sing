package com.singularity.todo.feature.notes

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks

/**
 * Result item returned by the internal link picker search.
 * Generic — the picker itself knows nothing about Note or Task domain types.
 *
 * @param id The ID string (noteId or taskId) — format depends on [kind].
 * @param title User-visible title shown in the list.
 * @param kind Discriminator for icon and URL scheme.
 */
data class LinkResult(val id: String, val title: String, val kind: LinkKind)

/**
 * What kind of thing a link points at.
 *
 * The scheme itself is not written here: it comes from [InternalLinkSchemes], the one
 * place that knows the app's link vocabulary.
 */
enum class LinkKind {
    Note,
    Task,
}

/**
 * The URL of the thing this kind of link points at.
 *
 * The picker builds the link it inserts into the editor with this, and the reader
 * of that link reads it back with [InternalLinks.parse] — the two agree because
 * both go through the same vocabulary.
 *
 * @param id the identifier of the target.
 */
fun LinkKind.urlFor(id: String): String = InternalLinks.urlFor(
    when (this) {
        LinkKind.Note -> InternalLinkSchemes.NOTE
        LinkKind.Task -> InternalLinkSchemes.TASK
    },
    id,
)
