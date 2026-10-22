package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.nav.TasksRoute
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import kotlin.time.Clock
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Task detail screen — navigation entry for the tasks nested navigation graph.
 */
@Composable
fun TaskDetailScreen(route: TasksRoute.Detail, modifier: Modifier = Modifier) {
    // The route, not the task id: the route also says which saved agenda view the
    // task was opened from, and the reminder set on the task points back at it.
    val coordinator: TaskDetailCoordinator = koinViewModel { parametersOf(route) }
    // The nav entry resolves "now" once and hands it down. The detail screen and
    // the editor nested inside it must agree about what "now" is, and reading the
    // clock in each of them would leave two independent answers (#91).
    val clock: Clock = koinInject()
    TaskDetailContent(
        coordinator = coordinator,
        modifier = modifier,
        now = clock.now(),
    )
}
