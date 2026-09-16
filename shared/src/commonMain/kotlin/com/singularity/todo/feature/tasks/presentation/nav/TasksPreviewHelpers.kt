package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * A [TasksNavigator] subclass with all navigation methods as no-ops.
 * Used in @Preview composables to avoid needing a real [NavBackStack].
 *
 * @see TasksPreviewWrapper
 */
class PreviewTasksNavigator(
    private val onOpenProject: (ProjectId) -> Unit = {},
) : TasksNavigator(
    backStack = NavBackStack(TasksRoute.Inbox(), TasksRoute.Inbox()),
    onExitGraph = {},
) {
    override fun openDetail(id: TaskId) { /* no-op for preview */ }
    override fun openCreate(initialDueDate: LocalDate?) { /* no-op for preview */ }
    override fun openUpcoming(date: LocalDate) { /* no-op for preview */ }
    override fun openProject(projectId: ProjectId) { onOpenProject(projectId) }
    override fun back() { /* no-op for preview */ }
    override fun closeGraph() { /* no-op for preview */ }
}

/**
 * Provides [PreviewTasksNavigator] to descendant @Preview composables.
 *
 * Usage:
 * ```
 * @Preview
 * @Composable
 * private fun TaskDetailViewScreenPreview() = TasksPreviewWrapper {
 *     TaskDetailViewScreen(taskId = TaskId("t1"))
 * }
 * ```
 */
@Composable
fun TasksPreviewWrapper(
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTasksNavigator provides PreviewTasksNavigator(),
        content = content,
    )
}
