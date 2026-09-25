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
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
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
        vm.events.collect { event ->
            if (event is com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiEvent.Saved) {
                isNavigatingBack = true
                navigator.back()
            }
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
        model = TaskEditorModel(
            taskId = null,
            titleDraft = state.draft.title,
            descriptionDraft = state.draft.description,
            priority = state.draft.priority,
            dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
            dueTime = state.draft.dueTime,
            startDate = null,
            startTime = null,
            project = null,
            tags = emptyList(),
            checklist = emptyList(),
            attachments = emptyList(),
            recurrence = null,
            isPinned = false,
            dependsOn = emptySet(),
            availableTasks = emptyList(),
        ),
        callbacks = TaskEditorCallbacks(
            onBack = guardedBack,
            onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
            onCheckToggle = {},
            onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
            priority = RowCallbacks(
                onChange = { vm.onIntent(TaskCreateIntent.SetPriority(it)) },
                onClick = { sheets.show(TaskEditorSheet.Priority) },
                onClear = { vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.None)) },
            ),
            dueDate = DateRowCallbacks(
                onChangeDate = { vm.onIntent(TaskCreateIntent.SetDueDate(it)) },
                onChangeTime = { vm.onIntent(TaskCreateIntent.SetDueTime(it)) },
                onClick = { sheets.show(TaskEditorSheet.Date) },
                onClear = { vm.onIntent(TaskCreateIntent.DueDateCleared) },
            ),
            startDate = null,
            project = null,
            tags = null,
            recurrence = null,
            pin = null,
            dependencies = null,
            checklist = null,
            attachments = null,
            bottomBar = {
                TaskSaveBar(
                    isEnabled = state.isSaveEnabled,
                    isLoading = state.isSaving,
                    onSaveClick = { vm.onIntent(TaskCreateIntent.SaveClicked) },
                )
            },
            menuItems = emptyList(),
        ),
        isCompleted = false,
    )
}
