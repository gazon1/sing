package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskCreateScreen(initialDueDate: LocalDate?) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(initialDueDate) }
    val navigator = LocalTasksNavigator.current

    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val sheets = rememberDialogState<TaskEditorSheet>()

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
        when {
            isNavigatingBack -> Unit
            state.isSaving -> Unit
            state.isDirty -> showDiscard = true
            else -> navigator.back()
        }
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

    TaskEditorContent(
        titleDraft = state.draft.title,
        onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
        isCompleted = false,
        onCheckToggle = { },
        descriptionDraft = state.draft.description,
        onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
        priority = state.draft.priority,
        onPrioritySelect = { vm.onIntent(TaskCreateIntent.SetPriority(it)) },
        onPriorityClear = { vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.None)) },
        dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
        dueTime = state.draft.dueTime,
        onDueDateSelect = { vm.onIntent(TaskCreateIntent.SetDueDate(it)) },
        onDueDateClear = { vm.onIntent(TaskCreateIntent.DueDateCleared) },
        onDueTimeSelect = { vm.onIntent(TaskCreateIntent.SetDueTime(it)) },
        showDueDate = true,
        onPriorityClick = { sheets.show(TaskEditorSheet.Priority) },
        onDueDateClick = { sheets.show(TaskEditorSheet.Date) },
        extraSections = null,
        onSetDependencies = null,
        bottomBar = {
            TaskSaveBar(
                isEnabled = state.isSaveEnabled,
                isLoading = state.isSaving,
                onSaveClick = { vm.onIntent(TaskCreateIntent.SaveClicked) },
            )
        },
        menuItems = emptyList(),
        onBack = guardedBack,
    )

    // Sheets
    when (sheets.active) {
        is TaskEditorSheet.Date -> com.singularity.todo.core.ui.components.DatePickerSheet(
            initialDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
            onDateSelected = { date ->
                vm.onIntent(TaskCreateIntent.SetDueDate(date))
                sheets.dismiss()
            },
            onDismiss = { sheets.dismiss() },
        )

        is TaskEditorSheet.Time -> com.singularity.todo.core.ui.components.TimePickerSheet(
            initialTime = state.draft.dueTime,
            onTimeSelected = { time ->
                vm.onIntent(TaskCreateIntent.SetDueTime(time))
                sheets.dismiss()
            },
            onDismiss = { sheets.dismiss() },
        )

        is TaskEditorSheet.Priority -> com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost(
            title = "Приоритет",
            onClose = { sheets.dismiss() },
        ) {
            com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorPrioritySheet(
                selected = state.draft.priority,
                onSelect = { p ->
                    vm.onIntent(TaskCreateIntent.SetPriority(p))
                    sheets.dismiss()
                },
            )
        }

        is TaskEditorSheet.Dependencies -> { /* not supported in create mode */ }

        null -> { /* no-op */ }
    }
}
