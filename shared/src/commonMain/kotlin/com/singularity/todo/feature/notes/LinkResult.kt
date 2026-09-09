package com.singularity.todo.feature.notes

/**
 * Result item returned by the internal link picker search.
 * Generic — the picker itself knows nothing about Note or Task domain types.
 *
 * @param id The ID string (noteId or taskId) — format depends on [kind].
 * @param title User-visible title shown in the list.
 * @param kind Discriminator for icon and URL scheme.
 */
data class LinkResult(
    val id: String,
    val title: String,
    val kind: LinkKind,
)

enum class LinkKind {
    Note,
    Task,
}
