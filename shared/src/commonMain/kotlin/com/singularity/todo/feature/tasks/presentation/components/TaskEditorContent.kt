package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.tasks.domain.model.ChecklistItemUi
import com.singularity.todo.feature.tasks.domain.model.PendingAttachment
import com.singularity.todo.feature.tasks.domain.model.TaskEditorMode
import com.singularity.todo.feature.tasks.domain.model.TaskEditorUiState
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Main task editor body — LazyColumn scaffold that composes all sections.
 *
 * ## Migration (2026-09)
 *
 * Formerly accepted 17 individual callback parameters. Now accepts a single
 * [TaskEditorActions] parameter. The old signature is preserved as a deprecated
 * overload that forwards to this version.
 */
@Composable
fun TaskEditorContent(
    state: TaskEditorUiState,
    actions: TaskEditorActions,
    modifier: Modifier = Modifier,
) {
    EditorContent(state = state, actions = actions, modifier = modifier)
}

/**
 * @deprecated Use [TaskEditorContent](state, actions, modifier). This overload
 *   exists only for backward compatibility during the migration period.
 */
@Deprecated(
    message = "Use TaskEditorContent(state, actions)",
    replaceWith = ReplaceWith(
        "TaskEditorContent(state, actions, modifier)",
        "com.singularity.todo.feature.tasks.components.TaskEditorActions",
    ),
)
@Composable
fun TaskEditorContent(
    state: TaskEditorUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriorityClick: () -> Unit,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onDatePreset: (LocalDate) -> Unit,
    onClearDate: () -> Unit,
    onClearTime: () -> Unit,
    onProjectClick: () -> Unit,
    onTagsClick: () -> Unit,
    onClearProject: () -> Unit,
    onNewChecklistItemChange: (String) -> Unit,
    onAddChecklistItem: () -> Unit,
    onToggleChecklistItem: (String) -> Unit,
    onDeleteChecklistItem: (String) -> Unit,
    onReminderClick: () -> Unit,
    onAttachmentClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = TaskEditorActions { action ->
        when (action) {
            is TaskEditorActions.Action.TitleChange -> onTitleChange(action.title)
            is TaskEditorActions.Action.DescriptionChange -> onDescriptionChange(action.description)
            is TaskEditorActions.Action.OpenPriorityPicker -> onPriorityClick()
            is TaskEditorActions.Action.OpenKindPicker -> {} // not wired in legacy path
            is TaskEditorActions.Action.OpenDatePicker -> onDateClick()
            is TaskEditorActions.Action.OpenTimePicker -> onTimeClick()
            is TaskEditorActions.Action.DatePreset -> onDatePreset(action.date)
            is TaskEditorActions.Action.ClearDate -> onClearDate()
            is TaskEditorActions.Action.ClearTime -> onClearTime()
            is TaskEditorActions.Action.OpenProjectPicker -> onProjectClick()
            is TaskEditorActions.Action.OpenTagsPicker -> onTagsClick()
            is TaskEditorActions.Action.ClearProject -> onClearProject()
            is TaskEditorActions.Action.RemoveTag -> {} // not wired in legacy path
            is TaskEditorActions.Action.NewChecklistItemChange -> onNewChecklistItemChange(action.text)
            is TaskEditorActions.Action.AddChecklistItem -> onAddChecklistItem()
            is TaskEditorActions.Action.ToggleChecklistItem ->
                onToggleChecklistItem(action.id)
            is TaskEditorActions.Action.DeleteChecklistItem ->
                onDeleteChecklistItem(action.id)
            is TaskEditorActions.Action.OpenReminderPicker -> onReminderClick()
            is TaskEditorActions.Action.OpenAttachmentPicker -> onAttachmentClick()
            is TaskEditorActions.Action.RemoveAttachment -> onRemoveAttachment(action.id)
        }
    }
    EditorContent(state = state, actions = actions, modifier = modifier)
}

@Composable
private fun EditorContent(
    state: TaskEditorUiState,
    actions: TaskEditorActions,
    modifier: Modifier = Modifier,
) {
    // Reference date for Today/Tomorrow presets; computed once.
    val today = remember { todayInSystemZone() }

    if (state.loading) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { CircularProgressIndicator() }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Title + description
        item {
            TaskEditorTitleSection(
                title = state.title,
                description = state.description,
                isError = state.errorMessage != null,
                errorMessage = state.errorMessage,
                onTitleChange = actions::onTitleChange,
                onDescriptionChange = actions::onDescriptionChange,
                requestFocus = state.mode is TaskEditorMode.New,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Priority row
        item {
            TaskEditorPriorityRow(
                priority = state.priority,
                onClick = actions::onOpenPriorityPicker,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Date / time
        item {
            TaskEditorDateTimeSection(
                dueDate = state.dueDate,
                dueTime = state.dueTime,
                today = today,
                onDateClick = actions::onOpenDatePicker,
                onTimeClick = actions::onOpenTimePicker,
                onDatePreset = actions::onDatePreset,
                onClearDate = actions::onClearDate,
                onClearTime = actions::onClearTime,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Project + Tags
        item {
            TaskEditorOrganizationSection(
                projectLabel = null,
                tagLabels = state.tagIds.map { "Tag" },
                onProjectClick = actions::onOpenProjectPicker,
                onTagsClick = actions::onOpenTagsPicker,
                onClearProject = actions::onClearProject,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Checklist
        item {
            TaskEditorChecklistSection(
                items = state.checklistItems,
                newItemText = state.newChecklistItem,
                onNewItemChange = actions::onNewChecklistItemChange,
                onAddItem = actions::onAddChecklistItem,
                onToggleItem = actions::onToggleChecklistItem,
                onDeleteItem = actions::onDeleteChecklistItem,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Reminder
        item {
            TaskEditorReminderSection(
                reminderOffset = state.reminderOffset,
                onClick = actions::onOpenReminderPicker,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Attachments
        item {
            TaskEditorAttachmentsSection(
                attachments = state.pendingAttachments,
                onAddClick = actions::onOpenAttachmentPicker,
                onRemoveAttachment = actions::onRemoveAttachment,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // Bottom spacer for keyboard
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun TaskEditorContentPreview() {
    val state = TaskEditorUiState(
        mode = TaskEditorMode.New,
        title = "Buy groceries",
        description = "Milk, eggs, bread, butter",
        priority = TaskPriority.High,
        dueDate = LocalDate(2026, 9, 15),
        dueTime = LocalTime(10, 0),
        checklistItems = listOf(
            ChecklistItemUi(id = "c1", title = "Milk", isCompleted = false),
            ChecklistItemUi(id = "c2", title = "Eggs", isCompleted = true),
        ),
        newChecklistItem = "",
        reminderOffset = ReminderOffset.FIFTEEN_MIN,
        pendingAttachments = listOf(
            PendingAttachment(path = "/path/file.pdf", name = "receipt.pdf", mimeType = "application/pdf"),
        ),
    )
    val actions = TaskEditorActions { }
    TaskEditorContent(state = state, actions = actions)
}
