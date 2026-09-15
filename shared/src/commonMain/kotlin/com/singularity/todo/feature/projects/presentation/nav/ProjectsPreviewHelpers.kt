package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * A [ProjectsNavigator] subclass with all navigation methods as no-ops.
 * Used in @Preview composables to avoid needing a real [NavBackStack].
 *
 * @see ProjectsPreviewWrapper
 */
class PreviewProjectsNavigator(
    private val onOpenTasks: (ProjectId) -> Unit = {},
    private val onOpenTask: (TaskId) -> Unit = {},
) : ProjectsNavigator(
    backStack = NavBackStack<ProjectsRoute>(ProjectsRoute.List, ProjectsRoute.List),
    onExitGraph = {},
) {
    override fun openDetail(id: ProjectId) { /* no-op for preview */ }
    override fun openEditor(id: ProjectId?) { /* no-op for preview */ }
    override fun openTasks(projectId: ProjectId) { onOpenTasks(projectId) }
    override fun openTask(taskId: TaskId) { onOpenTask(taskId) }
    override fun back() { /* no-op for preview */ }
    override fun closeGraph() { /* no-op for preview */ }
}

/**
 * Provides [PreviewProjectsNavigator] to descendant @Preview composables.
 *
 * Usage:
 * ```
 * @Preview
 * @Composable
 * private fun ProjectDetailScreenPreview() = ProjectsPreviewWrapper {
 *     ProjectDetailContent(viewModel = vm, projectId = ProjectId("p1"), ...)
 * }
 * ```
 */
@Composable
fun ProjectsPreviewWrapper(
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalProjectsNavigator provides PreviewProjectsNavigator(),
        content = content,
    )
}
