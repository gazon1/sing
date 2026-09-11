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
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailMode
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Thin host for TaskDetail Create mode.
 * Owns the VM lifecycle and handles navigation guard.
 */
@Composable
fun TaskCreateHost(
    mode: TaskDetailMode.Create,
    onBack: () -> Unit,
) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(mode.initialDueDate) }

    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.saved.collect {
            isNavigatingBack = true
            onBack()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val guardedBack: () -> Unit = {
        if (isNavigatingBack) Unit
        else if (state.isDirty) showDiscard = true
        else onBack()
    }

    if (showDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscard = false
                onBack()
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
