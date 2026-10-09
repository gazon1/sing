package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.core.ui.onboarding.SpotlightContent
import com.singularity.todo.core.ui.onboarding.SpotlightOverlay
import com.singularity.todo.core.ui.onboarding.rememberSpotlightTour
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.PendingDelete
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
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()
    val countdownProgress by vm.countdownProgress.collectAsStateWithLifecycle()

    val navigator = LocalAgendaNavigator.current
    val seedStore: SavedAgendaSeedStore = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    UndoSnackbar(
        pendingDelete = pendingDelete,
        snackbarHostState = snackbarHostState,
        onUndo = { vm.onIntent(AgendaIntent.UndoDeleteTapped) },
    )

    CollectEvents(vm.events) { event ->
        when (event) {
            is AgendaUiEvent.NavigateToTask -> navigator.openTask(event.taskId)

            is AgendaUiEvent.ShowTaskContextMenu -> navigator.showTaskContextMenu(event.taskId)

            is AgendaUiEvent.ExpandTask -> { /* expand handled by AgendaContent via routing state */ }

            is AgendaUiEvent.CreateInSection -> navigator.openCreateInSection(event.sectionId)

            is AgendaUiEvent.ShowError -> {
                scope.launch {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                }
            }

            is AgendaUiEvent.BulkOperationDone -> {
                val message = if (event.error != null) {
                    "Failed to ${event.operation}: ${event.error}"
                } else {
                    "${event.count} task${if (event.count != 1) "s" else ""} ${event.operation}"
                }
                scope.launch {
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                }
            }
        }
    }

    // Created here so the elements inside `AgendaContent` and the overlay below agree on
    // one registry; giving either side its own would leave the tour waiting forever for a
    // target that registers somewhere else.
    val tour = rememberSpotlightTour()

    Scaffold(
        snackbarHost = { TaggedSnackbarHost(snackbarHostState, countdownProgress = countdownProgress) },
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
                spotlightRegistry = tour.registry,
                modifier = Modifier,
            )

            // Outside `AgendaContent` but inside the same Box, so the scrim covers the
            // screen it is explaining and not only the part below the app bar.
            val stepIndex = tour.state.stepIndex
            if (stepIndex != null && stepIndex in SpotlightContent.STEPS.indices) {
                SpotlightOverlay(
                    state = tour.state,
                    step = SpotlightContent.STEPS[stepIndex],
                    stepPosition = stepIndex,
                    stepCount = SpotlightContent.STEPS.size,
                    onNext = tour::next,
                    onSkip = tour::skip,
                )
            }
        }
    }
}

/**
 * Shows the undo affordance while a delete is pending, and takes it down when the
 * pending delete clears.
 *
 * The ViewModel's window is the only clock here. Material 3 offers no custom
 * duration, so pairing `UNDO_WINDOW_MS` with `Short` or `Long` only ever gets the
 * two approximately right — and either direction is a broken promise: an affordance
 * that outlives the window offers an Undo that silently does nothing, and one that
 * dies early denies an undo the user was still entitled to. So the snackbar is
 * presented `Indefinite` and dismissed from here when the marker clears, which
 * happens when the window expires *or* a reversal succeeds.
 *
 * That is also what keeps the retry affordance real: a failed reversal leaves the
 * marker set, so the snackbar stays up and the user can try again.
 */
@Composable
private fun UndoSnackbar(
    pendingDelete: PendingDelete?,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
) {
    LaunchedEffect(pendingDelete) {
        val pending = pendingDelete
        if (pending == null) {
            // No `SnackbarHostState.dismiss()` at this Material3 version — the handle
            // is on the shown item. This resolves the pending `showSnackbar` as
            // Dismissed, so expiry is never mistaken for the user taking the offer.
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        val result = snackbarHostState.showSnackbar(
            message = "\"${pending.taskTitle}\" deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}
