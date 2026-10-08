package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.runtime.Immutable
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.tags.domain.model.TagGroupId

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
 * @see ProjectDetailActions
 */
@Immutable
class CurrentProjectContent(
    val name: String,
    val color: Int,
    val icon: String?,
    val parentId: ProjectId?,
    val dueDate: kotlinx.datetime.LocalDate?,
    val isArchived: Boolean,
    val childProjects: List<Project>,
    val reminderOffset: ReminderOffset?,
    val actions: ProjectDetailActions,
    val tagGroups: List<TagGroupOption> = emptyList(),
    val inheritedTagGroupIds: Set<TagGroupId> = emptySet(),
)
