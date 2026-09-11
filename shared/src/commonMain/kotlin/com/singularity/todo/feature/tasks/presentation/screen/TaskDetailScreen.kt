package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailMode

/**
 * Unified screen for TaskDetail: View existing task or Create new task.
 * Thin dispatcher — delegates to mode-specific Host composables.
 */
@Composable
fun TaskDetailScreen(
    mode: TaskDetailMode,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onNavigateToProject: ((ProjectId) -> Unit)? = null,
    onNavigateToTask: ((TaskId) -> Unit)? = null,
) {
    when (mode) {
        is TaskDetailMode.View -> TaskDetailViewHost(
            mode = mode,
            onBack = onBack,
            onNavigateToProject = onNavigateToProject ?: { },
            onNavigateToTask = onNavigateToTask ?: { },
        )
        is TaskDetailMode.Create -> TaskCreateHost(
            mode = mode,
            onBack = onBack,
        )
    }
}
