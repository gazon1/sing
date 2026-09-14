package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskCreateContent
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Task create screen for the tasks nested navigation graph.
 * Reads [LocalTasksNavigator] for all navigation — no callbacks needed.
 *
 * Uses [org.koin.compose.viewmodel.koinViewModel] with [parametersOf] for
 * per-entry ViewModel scoping (requires [rememberViewModelStoreNavEntryDecorator]
 * in the NavDisplay entry decorators).
 */
@Composable
fun TaskCreateScreen(
    initialDueDate: LocalDate?,
) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(initialDueDate) }
    val navigator = LocalTasksNavigator.current

    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.saved.collect {
            isNavigatingBack = true
            navigator.back()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val guardedBack: () -> Unit = {
        if (isNavigatingBack) Unit
        else if (state.isDirty) showDiscard = true
        else navigator.back()
    }

    if (showDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscard = false
                navigator.back()
            },
            onKeepEditing = { showDiscard = false },
        )
    }

    TaskCreateContent(
        state = state,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        onIntent = { intent ->
            when (intent) {
                TaskCreateIntent.DiscardChanges -> { /* handled via showDiscard */ }
                else -> vm.onIntent(intent)
            }
        },
        onBack = guardedBack,
    )
}
