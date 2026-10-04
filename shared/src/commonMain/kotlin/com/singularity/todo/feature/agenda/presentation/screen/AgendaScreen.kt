package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaSeedStore
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import org.koin.compose.koinInject
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
 * @param contextMenuHost Slot for the task context menu: JVM right-click popup or
 *        Android long-press bottom sheet. Provided by the platform-specific
 *        [AgendaNavGraph][com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph]
 *        implementation; defaults to a no-op (previews).
 */
@Composable
fun AgendaScreen(
    definition: AgendaDefinition,
    modifier: Modifier = Modifier,
    contextMenuHost: @Composable (
        taskUi: TaskUi,
        offset: androidx.compose.ui.unit.DpOffset,
        onDismiss: () -> Unit,
        onIntent: (AgendaIntent) -> Unit,
    ) -> Unit = {
        _,
        _,
        _,
        _,
        ->
    },
) {
    val vm: AgendaViewModel = koinViewModel {
        parametersOf(definition)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()

    val navigator = LocalAgendaNavigator.current
    val seedStore: SavedAgendaSeedStore = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show undo snackbar when a delete is pending.
    LaunchedEffect(pendingDelete) {
        val pd = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "\"${pd.taskTitle}\" deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            vm.onUndoDeleteIntent()
        }
    }

    CollectEvents(vm.events) { event ->
        when (event) {
            is AgendaUiEvent.NavigateToTask -> navigator.openTask(event.taskId)
            is AgendaUiEvent.ShowTaskContextMenu -> navigator.showTaskContextMenu(event.taskId)
            is AgendaUiEvent.ExpandTask -> { /* expand handled by AgendaContent via routing state */ }
            is AgendaUiEvent.CreateInSection -> navigator.openCreateInSection(event.sectionId)
            is AgendaUiEvent.UndoDelete -> { /* handled by LaunchedEffect above */ }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            AgendaContent(
                state = state,
                title = vm.title,
                onIntent = vm::onIntent,
                onSavedViewsClick = { navigator.openSavedAgendaList() },
                onSaveCurrentClick = {
                    seedStore.setSeed(definition)
                    navigator.openSavedAgendaCreate(definition)
                },
                contextMenuHost = contextMenuHost,
                modifier = Modifier,
            )
        }
    }
}
