// `now` is a required parameter, passed on to `TimeEntryEditorSheet` and to the
// nested `TaskEditorContent`, exactly as `TaskDetailContent` does. This screen has
// no call site — `find-unwired-surfaces.py` reports it, and `dad11e6b`'s note says
// deleting another branch's deliberate carrier is the owner's call, not this one's.
// It is threaded anyway because `TaskEditorContent` now requires the argument and
// this file calls it: leaving the read here would keep a wall-clock call alive in a
// screen nobody can currently reach, which is the shape that gets re-wired later
// with the defect intact.

package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.FirstRunSection
import com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard
import com.singularity.todo.feature.tasks.presentation.components.detail.LogbookSection
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.SubtasksSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorMenuItem
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.FirstRun
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.feature.timetracking.presentation.components.TimeEntryEditorSheet
import com.singularity.todo.feature.timetracking.presentation.components.TimeTrackingSection
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Instant

/**
 * Task detail screen (View mode) for the tasks nested navigation graph.
 */
@Composable
fun TaskDetailViewScreen(
    taskId: com.singularity.todo.feature.tasks.domain.model.TaskId,
    now: Instant,
) {
    val vm: TaskDetailCoordinator = koinViewModel { parametersOf(taskId) }
    val navigator = LocalTasksNavigator.current
    val state by vm.state.collectAsStateWithLifecycle()

    NotificationHost(
        events = vm.events,
        mapper = { event: TaskDetailUiEvent ->
            when (event) {
                is TaskDetailUiEvent.Saved -> Notification.None

                is TaskDetailUiEvent.Error -> Notification.Error(event.message)

                is TaskDetailUiEvent.UndoDelete -> Notification.Undo(
                    title = "Task deleted",
                    actionLabel = "Undo",
                    onAction = { vm.onIntent(TaskDetailIntent.Domain.Restore) },
                )

                TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
            }
        },
        onNavigateBack = { navigator.back() },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            TaskDetailUiState.Loading -> LoadingState()

            is TaskDetailUiState.Error -> ErrorState(
                message = s.message,
                onRetry = { vm.retry() },
            )

            is TaskDetailUiState.Loaded -> {
                val ui = s.ui
                val extras = s.extras
                var showAiSheet by rememberSaveable { mutableStateOf(false) }
                var showTimeEntrySheet by rememberSaveable { mutableStateOf(false) }

                TaskEditorContent(
                    taskId = ui.task.id.value,
                    titleDraft = ui.titleDraft,
                    onTitleChange = { vm.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                    isCompleted = ui.task.isCompleted,
                    onCheckToggle = { vm.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                    descriptionDraft = ui.descriptionDraft,
                    onDescriptionChange = { vm.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) },
                    priority = ui.task.priority,
                    onPrioritySelect = { vm.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                    onPriorityClear = { vm.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.None)) },
                    dueDate = ui.task.dueDate,
                    dueTime = ui.task.dueTime,
                    onDueDateSelect = { vm.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                    onDueDateClear = {
                        vm.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
                        vm.onIntent(TaskDetailIntent.Domain.SetDueTime(null))
                    },
                    onDueTimeSelect = { vm.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                    showDueDate = true,
                    onPriorityClick = null,
                    onDueDateClick = null,
                    estimateMinutes = ui.task.estimateMinutes,
                    estimateCallbacks = RowCallbacks(
                        onChange = { vm.onIntent(TaskDetailIntent.Domain.SetEstimate(it)) },
                        onClick = null,
                        onClear = { vm.onIntent(TaskDetailIntent.Domain.SetEstimate(null)) },
                    ),
                    dependsOn = ui.dependsOn,
                    availableTasks = ui.availableTasks,
                    extraSections = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val firstRun = when (val ex = extras) {
                                is TaskDetailExtras.Unresolved -> FirstRun.Unresolved
                                is TaskDetailExtras.Ready -> ex.firstRun
                            }
                            if (firstRun is FirstRun.Offer) {
                                FirstRunSection(
                                    onWriteNote = { /* scroll to body */ },
                                    onAddChecklist = { /* expand checklist */ },
                                    onAskAi = { showAiSheet = true },
                                )
                            }
                            val proposals = when (val ex = extras) {
                                is TaskDetailExtras.Unresolved -> emptyList()
                                is TaskDetailExtras.Ready -> ex.proposals
                            }
                            if (proposals.isNotEmpty()) {
                                ProposalSection(
                                    proposals = proposals,
                                    onConfirm = { itemId ->
                                        vm.onIntent(TaskDetailIntent.Domain.ConfirmProposalItem(itemId))
                                    },
                                    onReject = { itemId, reason ->
                                        vm.onIntent(TaskDetailIntent.Domain.RejectProposalItem(itemId, reason))
                                    },
                                    onConfirmAll = { proposalId ->
                                        vm.onIntent(TaskDetailIntent.Domain.ConfirmAllProposalItems(proposalId))
                                    },
                                    onDismiss = { proposalId ->
                                        vm.onIntent(TaskDetailIntent.Domain.DismissProposal(proposalId))
                                    },
                                )
                            }
                            TagsSection(
                                tags = ui.tags,
                                onDeleteTag = { vm.onIntent(TaskDetailIntent.Domain.RemoveTag(it)) },
                            )
                            if (ui.task.recurrence != null) {
                                RecurrenceSection(recurrence = ui.task.recurrence)
                            }
                            ChecklistSection(
                                checklist = ui.checklist,
                                onToggle = { vm.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(it)) },
                                onDelete = { vm.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(it)) },
                            )
                            SubtasksSection(
                                subtasks = ui.subtasks,
                                onToggle = { vm.onIntent(TaskDetailIntent.Domain.ToggleSubtask(it)) },
                                onDelete = { vm.onIntent(TaskDetailIntent.Domain.DeleteSubtask(it)) },
                                onOpen = { navigator.openDetail(it.id) },
                            )
                            if (ui.attachments.isNotEmpty()) {
                                AttachmentsSection(attachments = ui.attachments)
                            }
                            // Backlinks were collected into TaskDetailUi by
                            // TaskBacklinksCollector but never rendered, so a task linked
                            // from a note showed nothing at all.
                            if (ui.linkedNotes.isNotEmpty() || ui.linkedTasks.isNotEmpty()) {
                                LinkedBacklinksCard(
                                    linkedNotes = ui.linkedNotes,
                                    linkedTasks = ui.linkedTasks,
                                    onOpenNote = { navigator.openNote(it) },
                                    onOpenTask = { navigator.openDetail(it) },
                                )
                            }
                            // Logbook: notes explicitly attached to this task via Note.taskId.
                            TimeTrackingSection(
                                state = ui.timeSlotState,
                                onStart = { vm.onIntent(TaskDetailIntent.Domain.Start) },
                                onStop = { vm.onIntent(TaskDetailIntent.Domain.Stop) },
                                onAddManual = { showTimeEntrySheet = true },
                            )
                            LogbookSection(
                                entries = ui.logbookEntries,
                                onOpenNote = { navigator.openNote(it) },
                                onAddNote = { taskId -> navigator.openCreateNote(taskId) },
                                currentTaskId = ui.task.id,
                            )
                        }
                    },
                    onSetDependencies = { vm.onIntent(TaskDetailIntent.Domain.SetDependencies(it)) },
                    bottomBar = null,
                    menuItems = buildDetailMenuItems(isTrashed = ui.task.isTrashed) { intent ->
                        vm.onIntent(intent)
                    },
                    onBack = { navigator.back() },
                    onAiClick = { showAiSheet = true },
                    now = now,
                )

                if (showAiSheet) {
                    TaskAiBottomSheet(
                        task = ui.task,
                        onAction = { action ->
                            vm.onIntent(TaskDetailIntent.Domain.RunAiAction(action))
                            showAiSheet = false
                        },
                        onDismiss = { showAiSheet = false },
                    )
                }

                if (showTimeEntrySheet) {
                    TimeEntryEditorSheet(
                        taskStartedAtMs = ui.task.createdAt.toEpochMilliseconds(),
                        now = now,
                        onSave = { startedAtMs, endedAtMs, kind, note ->
                            vm.onIntent(
                                TaskDetailIntent.Domain.CreateManual(
                                    startedAtMs = startedAtMs,
                                    endedAtMs = endedAtMs,
                                    kind = kind,
                                    note = note,
                                ),
                            )
                            showTimeEntrySheet = false
                        },
                        onDismiss = { showTimeEntrySheet = false },
                    )
                }
            }
        }
    }
}

// ─── Extra section composables ───────────────────────────────────────────────

@Composable
private fun TagsSection(tags: List<Tag>, onDeleteTag: (TagId) -> Unit) {
    if (tags.isEmpty()) return
    ExtraSectionCard(
        icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
        label = "Tags",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tags.take(5)
                .forEach { tag ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove tag",
                                modifier = Modifier.clickable { onDeleteTag(tag.id) }
                                    .height(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun RecurrenceSection(recurrence: RecurrenceSpec) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
        label = "Repeats",
    ) {
        Text(
            text = recurrence.toString(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChecklistSection(
    checklist: List<ChecklistItem>,
    onToggle: (ChecklistItem) -> Unit,
    onDelete: (ChecklistItemId) -> Unit,
) {
    if (checklist.isEmpty()) return
    ExtraSectionCard(
        icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
        label = "Checklist (${checklist.count { it.isCompleted }}/${checklist.size})",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            checklist.take(10)
                .forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onToggle(item) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.isCompleted) "✓ ${item.title}" else item.title,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                            color = if (item.isCompleted) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onDelete(item.id) },
                            modifier = Modifier.height(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete item",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            if (checklist.size > 10) {
                Text(
                    "+${checklist.size - 10} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttachmentsSection(attachments: List<Attachment>) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
        label = "Attachments (${attachments.size})",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            attachments.take(5)
                .forEach { att ->
                    Text(
                        text = att.displayTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
        }
    }
}

// ─── AI Proposals ─────────────────────────────────────────────────────────────

@Composable
private fun ProposalSection(
    proposals: List<AiProposal>,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: (ProposalId) -> Unit,
    onDismiss: (ProposalId) -> Unit,
) {
    proposals.forEach { proposal ->
        ProposalCard(
            proposal = proposal,
            onConfirm = onConfirm,
            onReject = onReject,
            onConfirmAll = { onConfirmAll(proposal.id) },
            onDismiss = { onDismiss(proposal.id) },
        )
    }
}

@Composable
private fun ProposalCard(
    proposal: AiProposal,
    onConfirm: (ProposalItemId) -> Unit,
    onReject: (ProposalItemId, String?) -> Unit,
    onConfirmAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rejectingItemId by mutableStateOf<ProposalItemId?>(null)
    var rejectionText by mutableStateOf("")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI Suggestions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Row {
                    TextButton(onClick = onConfirmAll) {
                        Text("Accept all")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
            }

            proposal.pendingItems.forEach { item ->
                ProposalItemRow(
                    item = item,
                    isRejecting = rejectingItemId == item.id,
                    rejectionText = if (rejectingItemId == item.id) rejectionText else "",
                    onConfirm = { onConfirm(item.id) },
                    onStartReject = { rejectingItemId = item.id },
                    onSendReject = {
                        onReject(item.id, rejectionText.takeIf { it.isNotBlank() })
                        rejectingItemId = null
                        rejectionText = ""
                    },
                    onCancelReject = {
                        rejectingItemId = null
                        rejectionText = ""
                    },
                    onRejectionTextChange = { rejectionText = it },
                )
            }
        }
    }
}

@Composable
private fun ProposalItemRow(
    item: com.singularity.todo.feature.proposals.domain.model.ProposalItem,
    isRejecting: Boolean,
    rejectionText: String,
    onConfirm: () -> Unit,
    onStartReject: () -> Unit,
    onSendReject: () -> Unit,
    onCancelReject: () -> Unit,
    onRejectionTextChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.humanSummary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (isRejecting) {
                TextButton(onClick = onSendReject) { Text("Send") }
                TextButton(onClick = onCancelReject) { Text("Cancel") }
            } else {
                TextButton(onClick = onConfirm) { Text("✓") }
                TextButton(onClick = onStartReject) { Text("✗") }
            }
        }
        if (isRejecting) {
            OutlinedTextField(
                value = rejectionText,
                onValueChange = onRejectionTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Reason (optional)") },
                singleLine = true,
            )
        }
    }
}

@Suppress("FunctionSignature")
@Composable
private fun ExtraSectionCard(icon: @Composable () -> Unit, label: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

// ─── Menu items ─────────────────────────────────────────────────────────────

private fun buildDetailMenuItems(
    isTrashed: Boolean,
    onIntent: (TaskDetailIntent.Domain) -> Unit,
): List<TaskEditorMenuItem> = if (isTrashed) {
    listOf(
        TaskEditorMenuItem(
            label = "Восстановить",
            onClick = { onIntent(TaskDetailIntent.Domain.Unarchive) },
            testTag = TestTags.EditorOverflow.RESTORE,
        ),
    )
} else {
    listOf(
        TaskEditorMenuItem(
            label = "Архивировать",
            onClick = { onIntent(TaskDetailIntent.Domain.Archive) },
            testTag = TestTags.EditorOverflow.ARCHIVE,
        ),
        TaskEditorMenuItem(
            label = "Удалить",
            onClick = { onIntent(TaskDetailIntent.Domain.Delete) },
            testTag = TestTags.EditorOverflow.DELETE,
        ),
    )
}

// ─── Loading / Error ─────────────────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}
