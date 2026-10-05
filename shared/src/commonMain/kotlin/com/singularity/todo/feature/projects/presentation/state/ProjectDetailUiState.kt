package com.singularity.todo.feature.projects.presentation.state
import androidx.compose.runtime.Immutable
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.tasks.domain.model.Task

@Immutable
sealed interface ProjectDetailUiState {
    data object Loading : ProjectDetailUiState
    data object NotFound : ProjectDetailUiState

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
        val parentOptions: List<ParentOption>,
        /** Active tasks outside this project, for the "add existing task" picker. */
        val availableTasks: List<Task>,
    ) : ProjectDetailUiState
}
