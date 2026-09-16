package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the Agenda screen.
 *
 * Uses [koinViewModel] to obtain the [AgendaViewModel] scoped to this nav entry.
 * Navigation events are handled via [LocalAgendaNavigator] provided by the nav graph.
 *
 * @param definition The [AgendaDefinition] to evaluate and display.
 * @param modifier Compose modifier for the screen container.
 * @param desktopContextMenuHost Slot for the desktop (JVM) context menu. On Android
 *        this is a no-op. On Desktop it is provided by the platform-specific
 *        [AgendaNavGraph][com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph]
 *        implementation.
 */
@Composable
fun AgendaScreen(
    definition: AgendaDefinition,
    modifier: Modifier = Modifier,
    desktopContextMenuHost: @Composable (taskUi: TaskUi, offset: androidx.compose.ui.unit.DpOffset, onDismiss: () -> Unit) -> Unit = { _, _, _ -> },
) {
    val vm: AgendaViewModel = koinViewModel {
        parametersOf(definition)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    val navigator = LocalAgendaNavigator.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is AgendaUiEvent.NavigateToTask -> navigator.openTask(event.taskId)
                is AgendaUiEvent.ShowTaskContextMenu -> navigator.showTaskContextMenu(event.taskId)
            }
        }
    }

    AgendaContent(
        state = state,
        title = vm.title,
        onIntent = vm::onIntent,
        desktopContextMenuHost = desktopContextMenuHost,
        modifier = modifier,
    )
}
