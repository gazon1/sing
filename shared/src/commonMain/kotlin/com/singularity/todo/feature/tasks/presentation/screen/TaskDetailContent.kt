// `now` is a required parameter, passed on to `TimeEntryEditorSheet` and to the
// nested `TaskEditorContent`. It used to be read here as `Clock.System.now()`
// behind a file-level suppression, whose recorded reason was that threading it is
// four signature changes across three screens "ending in a call no desktop Compose
// test on this host can execute (#201)". The four signatures are made; the reason
// described a difficulty rather than a blocker, and a suppression justified by work
// not yet done tends to outlive the work.

package com.singularity.todo.feature.tasks.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import kotlin.time.Instant
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.files.FilePickPurpose
import com.singularity.todo.core.files.rememberAppFilePicker
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.FirstRunSection
import com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard
import com.singularity.todo.feature.tasks.presentation.components.detail.LogbookSection
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.SubtasksSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailAttachmentsSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailChecklistSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailProposalSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailRecurrenceSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailTagsSection
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

@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionSignature")
@Composable
fun TaskDetailContent(
    coordinator: TaskDetailCoordinator,
    modifier: Modifier = Modifier,
    now: Instant,
) {
    val state by coordinator.stateFlow.collectAsStateWithLifecycle()
    val navigator = LocalTasksNavigator.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAiSheet by rememberSaveable { mutableStateOf(false) }
    // Separate flag rather than one `showSheet` enum: the two sheets are
    // reachable from two different sections far apart in the list, and an enum
    // would mean every call site names a state it does not own.
    var showTimeEntrySheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(coordinator.events) {
        coordinator.events.collect { event ->
            if (event is TaskDetailUiEvent.Saved) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    NotificationHost(
        events = coordinator.events,
        mapper = { event: TaskDetailUiEvent ->
            when (event) {
                is TaskDetailUiEvent.Saved -> Notification.None

                is TaskDetailUiEvent.Error -> Notification.Error(event.message)

                is TaskDetailUiEvent.UndoDelete -> Notification.Undo(
                    title = "Task deleted",
                    actionLabel = "Undo",
                    onAction = { coordinator.onIntent(TaskDetailIntent.Domain.Restore) },
                )

                TaskDetailUiEvent.NavigateBack -> Notification.NavigateBack
            }
        },
        onNavigateBack = { navigator.back() },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                TaskDetailUiState.Loading -> LoadingState()

                is TaskDetailUiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { coordinator.retry() },
                )

                is TaskDetailUiState.Loaded -> {
                    val ui = s.ui
                    val extras = s.extras

                    // The attach-file entry point. Declared here rather than inside the
                    // attachments card so the launcher survives recomposition with the
                    // same identity, and so the card stays a presentational component.
                    val pickAttachment = rememberAppFilePicker(FilePickPurpose.Attachment) { picked ->
                        if (picked != null) {
                            coordinator.onIntent(
                                TaskDetailIntent.Domain.AddFileAttachment(
                                    sourcePath = picked.path,
                                    mimeType = picked.mimeType,
                                ),
                            )
                        }
                    }

                    TaskEditorContent(
                        taskId = ui.task.id.value,
                        titleDraft = ui.titleDraft,
                        onTitleChange = { coordinator.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                        isCompleted = ui.task.isCompleted,
                        onCheckToggle = { coordinator.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                        descriptionDraft = ui.descriptionDraft,
                        onDescriptionChange = { coordinator.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) },
                        priority = ui.task.priority,
                        onPrioritySelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                        onPriorityClear = {
                            coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.None))
                        },
                        dueDate = ui.task.dueDate,
                        dueTime = ui.task.dueTime,
                        onDueDateSelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                        onDueDateClear = {
                            coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
                            coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(null))
                        },
                        onDueTimeSelect = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                        showDueDate = true,
                        onPriorityClick = null,
                        onDueDateClick = null,
                        startDate = null,
                        startTime = null,
                        startDateCallbacks = null,
                        project = null,
                        projectCallbacks = null,
                        tags = ui.tags.map { it.id },
                        tagsCallbacks = null,
                        recurrence = ui.task.recurrence,
                        recurrenceCallbacks = null,
                        isPinned = ui.task.isPinned,
                        pinCallbacks = null,
                        dependsOn = ui.dependsOn,
                        availableTasks = ui.availableTasks,
                        // Was absent on the desktop path while the Android-only
                        // screen had it, so an estimate set on one platform was
                        // invisible on the other. Both routes render this now.
                        estimateMinutes = ui.task.estimateMinutes,
                        estimateCallbacks = RowCallbacks(
                            onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetEstimate(it)) },
                            onClick = null,
                            onClear = { coordinator.onIntent(TaskDetailIntent.Domain.SetEstimate(null)) },
                        ),
                        extraSections = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // First-run nudge and pending AI proposals both
                                // arrive through `extras`, which is Unresolved
                                // until the coordinator's cold-start work lands.
                                // Reading them as absent while unresolved is the
                                // intended behaviour: there is nothing to show yet,
                                // and inventing an empty offer would flash a
                                // section that disappears a frame later.
                                val firstRun = when (val ex = extras) {
                                    is TaskDetailExtras.Unresolved -> FirstRun.Unresolved
                                    is TaskDetailExtras.Ready -> ex.firstRun
                                }
                                if (firstRun is FirstRun.Offer) {
                                    FirstRunSection(
                                        // Only Ask AI has a handler to give it. The
                                        // other two chips are not rendered — see
                                        // FirstRunSection's KDoc.
                                        onAskAi = { showAiSheet = true },
                                    )
                                }
                                val proposals = when (val ex = extras) {
                                    is TaskDetailExtras.Unresolved -> emptyList()
                                    is TaskDetailExtras.Ready -> ex.proposals
                                }
                                if (proposals.isNotEmpty()) {
                                    TaskDetailProposalSection(
                                        proposals = proposals,
                                        onConfirm = { itemId ->
                                            coordinator.onIntent(
                                                TaskDetailIntent.Domain.ConfirmProposalItem(itemId),
                                            )
                                        },
                                        onReject = { itemId, reason ->
                                            coordinator.onIntent(
                                                TaskDetailIntent.Domain.RejectProposalItem(itemId, reason),
                                            )
                                        },
                                        onConfirmAll = { proposalId ->
                                            coordinator.onIntent(
                                                TaskDetailIntent.Domain.ConfirmAllProposalItems(proposalId),
                                            )
                                        },
                                        onDismiss = { proposalId ->
                                            coordinator.onIntent(
                                                TaskDetailIntent.Domain.DismissProposal(proposalId),
                                            )
                                        },
                                    )
                                }
                                TaskDetailTagsSection(
                                    tags = ui.tags,
                                    onDeleteTag = { coordinator.onIntent(TaskDetailIntent.Domain.RemoveTag(it)) },
                                )
                                if (ui.task.recurrence != null) {
                                    TaskDetailRecurrenceSection(
                                        recurrence = ui.task.recurrence,
                                    )
                                }
                                TaskDetailChecklistSection(
                                    checklist = ui.checklist,
                                    onToggle = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(it))
                                    },
                                    onDelete = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.DeleteChecklistItem(it))
                                    },
                                )
                                SubtasksSection(
                                    subtasks = ui.subtasks,
                                    onToggle = { coordinator.onIntent(TaskDetailIntent.Domain.ToggleSubtask(it)) },
                                    onDelete = { coordinator.onIntent(TaskDetailIntent.Domain.DeleteSubtask(it)) },
                                    onOpen = { navigator.openDetail(it.id) },
                                )
                                // Always shown, not only when there is something to show: this row is also
                                // how the user attaches the first file.
                                TaskDetailAttachmentsSection(
                                    attachments = ui.attachments,
                                    onAttachFile = { pickAttachment() },
                                    onDelete = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.DeleteAttachment(it))
                                    },
                                    onOpen = { id -> navigator.openAttachment(id) },
                                )
                                if (ui.linkedNotes.isNotEmpty() || ui.linkedTasks.isNotEmpty()) {
                                    LinkedBacklinksCard(
                                        linkedNotes = ui.linkedNotes,
                                        linkedTasks = ui.linkedTasks,
                                        onOpenNote = { navigator.openNote(it) },
                                        onOpenTask = { navigator.openDetail(it) },
                                    )
                                }
                                TimeTrackingSection(
                                    state = ui.timeSlotState,
                                    onStart = { coordinator.onIntent(TaskDetailIntent.Domain.Start) },
                                    onStop = { coordinator.onIntent(TaskDetailIntent.Domain.Stop) },
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
                        onSetDependencies = { coordinator.onIntent(TaskDetailIntent.Domain.SetDependencies(it)) },
                        bottomBar = null,
                        menuItems = buildDetailMenuItems(isTrashed = ui.task.isTrashed) { intent ->
                            coordinator.onIntent(intent)
                        },
                        onBack = { navigator.back() },
                        onAiClick = { showAiSheet = true },
                        now = now,
                    )

                    if (showAiSheet) {
                        TaskAiBottomSheet(
                            task = ui.task,
                            onAction = { action ->
                                coordinator.onIntent(TaskDetailIntent.Domain.RunAiAction(action))
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
                                coordinator.onIntent(
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
        modifier = Modifier
            .fillMaxSize()
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
