package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Current project values a sheet needs to render itself, plus the [ProjectDetailActions]
 * dispatcher its `onPick`/`onConfirm` handlers call into.
 *
 * **Not a `data class`.** The previous shape held ten nullable callback fields; a generated
 * `equals` compares lambdas by identity, so two structurally identical instances could
 * never be equal and the class was only usable as an opaque token. Carrying the single
 * [actions] value class instead keeps the type honest — every sheet dispatches through the
 * same named helpers the rest of the screen uses, and no callback is optional.
 *
 * ## Why there is no `@Immutable` here
 *
 * The class carries three collections — the child projects, the tag groups and the
 * inherited tag group ids — and they are persistent now: each is a collection that
 * cannot change after it is built. `@Immutable` would promise something the class no
 * longer needs to be told twice, and the annotation was bought with a promise that was
 * false when it was written. The `NoFalseImmutable` rule keeps it from coming back.
 *
 * @see ProjectDetailActions
 * @see docs/architecture/compose-stability.md
 */
class CurrentProjectContent(
    val name: String,
    val color: Int,
    val icon: String?,
    val parentId: ProjectId?,
    val dueDate: kotlinx.datetime.LocalDate?,
    val isArchived: Boolean,
    /**
     * How many of the project's tasks are still open.
     *
     * The close sheet says it out loud: closing a project closes its open tasks
     * too, and a cascade the user did not ask for one task at a time deserves to
     * be counted before it happens.
     */
    val openTaskCount: Int = 0,
    val childProjects: ImmutableList<Project>,
    val reminderOffset: ReminderOffset?,
    /**
     * Preselects [ReminderPicker] when [reminderOffset] is null — the user's default
     * from Notification settings. Never creates a reminder on its own.
     */
    val defaultReminderOffset: ReminderOffset = ReminderOffset.AT_DUE,
    val actions: ProjectDetailActions,
    val tagGroups: ImmutableList<TagGroupOption> = persistentListOf(),
    val inheritedTagGroupIds: ImmutableSet<TagGroupId> = persistentSetOf(),
)
