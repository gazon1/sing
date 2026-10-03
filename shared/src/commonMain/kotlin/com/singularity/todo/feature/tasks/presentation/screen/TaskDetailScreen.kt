package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Task detail screen — navigation entry for the tasks nested navigation graph.
 */
@Composable
fun TaskDetailScreen(taskId: TaskId, modifier: Modifier = Modifier) {
    val coordinator: TaskDetailCoordinator = koinViewModel { parametersOf(taskId) }
    TaskDetailContent(
        coordinator = coordinator,
        modifier = modifier,
    )
}
