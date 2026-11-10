package com.singularity.todo.feature.tasks.domain.state

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate

/**
 * Everything a create screen is seeded with, as one value.
 *
 * ## Why this exists
 *
 * The screen and the Koin factory that builds its ViewModel used to exchange four
 * loose parameters — `initialDueDate`, `draftKey`, `initialProjectId`,
 * `initialTagIds` — through `koinViewModel { parametersOf(a, b, c, d) }`. Koin
 * matches those by *position and type*, not by name, and the factory destructured
 * them in the same order, in a different module. Two swapped fields of the same
 * type (`projectId` and `tagIds` were not, but the two `String?`-shaped slots
 * and every future same-typed pair are) compiled, kept every unit test green,
 * and failed only at runtime, inside the Koin graph, on a device.
 *
 * One value closes that: there is one parameter to pass, one parameter to
 * receive, and the fields inside it are wired by name on both sides. A swap on
 * either side is now a compile error or a named-argument mistake the compiler
 * sees, not a runtime surprise.
 *
 * @param initialDueDate the date the new task lands on, when the caller knows one
 *   (the Today tab); `null` from Inbox, Upcoming, and the project/tag agendas.
 * @param draftKey the key of the draft the editor restores from, or `null` for
 *   the generic create draft.
 * @param initialProjectId the project of the list the screen was opened from, so
 *   a task created from a project's agenda belongs to that project (#1952/#1958).
 * @param initialTagIds the tags of the list the screen was opened from, so a task
 *   created from a tag's or a multi-tag agenda belongs to those tags.
 */
data class TaskCreateSeed(
    val initialDueDate: LocalDate? = null,
    val draftKey: String? = null,
    val initialProjectId: ProjectId? = null,
    val initialTagIds: Set<TagId> = emptySet(),
)
