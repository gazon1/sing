package com.singularity.todo.feature.plans.presentation.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansIntent
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansUiEvent
import com.singularity.todo.feature.plans.presentation.viewmodel.PlansViewModel
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * The plan screen.
 *
 * The ViewModel comes from Koin, the state is observed here, and every action
 * the user takes leaves through a navigation to a real screen — the task's own
 * detail, or the editor with the date prefilled.
 *
 * The screen reads the app-level navigator rather than one of its own: Plans is
 * a tab, so there is no nested graph to be inside, and the destinations it
 * opens — a task, the editor — belong to the tasks feature.
 */
@Composable
fun PlansScreen(modifier: Modifier = Modifier) {
    val vm: PlansViewModel = koinViewModel()
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    CollectEvents(vm.events) { event ->
        when (event) {
            is PlansUiEvent.ShowError -> scope.launch {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    PlansContent(
        state = state,
        onIntent = vm::onIntent,
        onTaskClick = { taskId ->
            navigator.navigate(
                AppDestination.TasksGraph(AppDestination.TasksStartRoute.Detail(taskId)),
            )
        },
        onToggleCompleted = { taskId -> vm.onIntent(PlansIntent.ToggleCompleted(taskId)) },
        onSwipeAway = { taskId -> vm.onIntent(PlansIntent.TaskSwipedAway(taskId)) },
        onAddInSection = { dueDate ->
            navigator.navigate(
                AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create(), dueDate),
            )
        },
        snackbarHost = { TaggedSnackbarHost(snackbarHostState) },
        modifier = modifier,
    )
}
