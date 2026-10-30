package com.singularity.todo.feature.projects.presentation.state
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.tasks.domain.model.Task
import kotlinx.collections.immutable.ImmutableList

sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState
    data object NotFound : ProjectDetailUiState
    data class Error(val message: String) : ProjectDetailUiState

    /**
     * Everything the screen renders, in one snapshot.
     *
     * [parentOptions] and [availableTasks] used to be separate public flows on the
     * ViewModel. Folding them in means the screen collects a single state instead of
     * four independently-timed flows, which is what let the UI briefly show a
     * `hideCompleted` toggle that disagreed with the task list already on screen.
     */
    data class Content(
        val ui: ProjectDetailUi,
        /** Whether completed tasks are filtered out of [ProjectDetailUi.tasks]. */
        val hideCompleted: Boolean,
        /**
         * Whether tasks blocked by unfinished dependencies are filtered out of
         * [ProjectDetailUi.tasks]. Defaults to off, so a task the user could
         * previously see is never dropped without them asking.
         */
        val hideBlocked: Boolean = false,
        /** Parent-picker options; excludes this project, deleted and non-root projects. */
        val parentOptions: ImmutableList<ParentOption>,
        /** Active tasks outside this project, for the "add existing task" picker. */
        val availableTasks: ImmutableList<Task>,
        /** All tag group options for the inherited-groups picker; excludes soft-deleted groups. */
        val tagGroups: ImmutableList<TagGroupOption>,
        /**
         * A close that was refused until the user confirms it, because one of the
         * project's tasks has an unfinished prerequisite.
         *
         * State, not an event: the confirmation has to survive a rotation, and a
         * one-shot event is gone by the time the screen comes back.
         */
        val pendingCloseConfirmation: ProjectCloseConfirmation? = null,
    ) : ProjectDetailUiState
}

/**
 * A project close waiting for the user's confirmation.
 *
 * ## Why there is no `@Immutable` here
 *
 * The class carries a list of task titles, and it is a persistent list now — a
 * collection that cannot change after it is built. `@Immutable` would promise
 * something the class no longer needs to be told twice, and the annotation was
 * bought with a promise that was false when it was written. The
 * `NoFalseImmutable` rule keeps it from coming back.
 *
 * @property blockedTaskTitles the titles of the tasks that are in the way.
 * @property openTaskCount how many of the project's tasks the close will close.
 */
data class ProjectCloseConfirmation(val blockedTaskTitles: ImmutableList<String>, val openTaskCount: Int)
