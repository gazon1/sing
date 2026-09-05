package com.singularity.todo.feature.tasks.components

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
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.feature.tasks.TaskEditorMode
import com.singularity.todo.feature.tasks.TaskEditorUiState
import kotlinx.datetime.LocalDate

/**
 * Main task editor body — LazyColumn scaffold that composes all sections.
 */
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
                onTitleChange = onTitleChange,
                onDescriptionChange = onDescriptionChange,
                requestFocus = state.mode is TaskEditorMode.New,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Priority row
        item {
            TaskEditorPriorityRow(
                priority = state.priority,
                onClick = onPriorityClick,
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
                onDateClick = onDateClick,
                onTimeClick = onTimeClick,
                onDatePreset = onDatePreset,
                onClearDate = onClearDate,
                onClearTime = onClearTime,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Project + Tags
        item {
            TaskEditorOrganizationSection(
                projectLabel = null,
                tagLabels = state.tagIds.map { "Tag" },
                onProjectClick = onProjectClick,
                onTagsClick = onTagsClick,
                onClearProject = onClearProject,
                onRemoveTag = { },
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Checklist
        item {
            TaskEditorChecklistSection(
                items = state.checklistItems,
                newItemText = state.newChecklistItem,
                onNewItemChange = onNewChecklistItemChange,
                onAddItem = onAddChecklistItem,
                onToggleItem = onToggleChecklistItem,
                onDeleteItem = onDeleteChecklistItem,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Reminder
        item {
            TaskEditorReminderSection(
                reminderOffset = state.reminderOffset,
                onClick = onReminderClick,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Attachments
        item {
            TaskEditorAttachmentsSection(
                attachments = state.pendingAttachments,
                onAddClick = onAttachmentClick,
                onRemoveAttachment = onRemoveAttachment,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // Bottom spacer for keyboard
        item { Spacer(Modifier.height(80.dp)) }
    }
}
