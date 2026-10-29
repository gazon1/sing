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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.files.FilePickPurpose
import com.singularity.todo.core.files.rememberAppFilePicker
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BlockedCompletionDialog
import com.singularity.todo.core.ui.components.formatDateCount
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.TaskAiBottomSheet
import com.singularity.todo.feature.tasks.presentation.components.detail.AttachmentsCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.FirstRunSection
import com.singularity.todo.feature.tasks.presentation.components.detail.LinkedBacklinksCard
import com.singularity.todo.feature.tasks.presentation.components.detail.LogbookSection
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.ToggleCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.SubtasksSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailAttachmentsSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailChecklistSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailProposalSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailRecurrenceSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskDetailTagsSection
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorMenuItem
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSeriesCard
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.presentation.state.FirstRun
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailExtras
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailCoordinator
import com.singularity.todo.feature.timetracking.presentation.components.TimeEntryEditorSheet
import com.singularity.todo.feature.timetracking.presentation.components.TimeTrackingSection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.runtime.getValue

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
    val sheets = rememberDialogState<TaskEditorSheet>()

    // One collector, one owner.
    //
    // The events live in a channel, and a channel has one consumer: two
    // collectors of the same flow split the events between them, and each
    // collector keeps only the ones it recognises. This screen had two — one
    // for the "saved" message, one inside NotificationHost — so whether the
    // undo toast appeared was a matter of which collector the event landed in.
    // The delete flow measured it landing wrong: after "Удалить" the undo
    // toast was not on the screen at all.
    //
    // So the screen collects once and hands the notifications over.
    val notifications = remember { Channel<Notification>(Channel.BUFFERED) }

    LaunchedEffect(coordinator.events) {
        coordinator.events.collect { event ->
            when (event) {
                is TaskDetailUiEvent.Saved -> scope.launch {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }

                is TaskDetailUiEvent.Error -> notifications.send(Notification.Error(event.message))

                is TaskDetailUiEvent.UndoDelete -> notifications.send(
                    Notification.Undo(
                        title = "Task deleted",
                        actionLabel = "Undo",
                        onAction = { coordinator.onIntent(TaskDetailIntent.Domain.Restore) },
                    ),
                )

                is TaskDetailUiEvent.UndoDeleteSeries -> notifications.send(
                    Notification.Undo(
                        title = "Series deleted · ${formatDateCount(event.dates)}",
                        actionLabel = "Undo",
                        onAction = {
                            coordinator.onIntent(TaskDetailIntent.Domain.RestoreSeries(event.taskIds))
                        },
                        // The screen leaves when the offer is declined — not before it
                        // is made. The toast lives in this screen's composition, so a
                        // navigation that came first would destroy the very offer it
                        // was supposed to accompany: the user would see the series
                        // archived and no way back.
                        onDeclined = { navigator.back() },
                    ),
                )

                TaskDetailUiEvent.NavigateBack -> notifications.send(Notification.NavigateBack)
            }
        }
    }

    // The system back press means what the editor's back button means — and
    // with the keyboard open it means "close the keyboard" first. The task
    // detail edits its title and its description in place, so a back press
    // that leaves the screen while the keyboard is up throws the edit away.
    ImeAwareBackHandler {
        coordinator.flushDraft()
        navigator.back()
    }

    NotificationHost(
        events = notifications.receiveAsFlow(),
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
            RefreshableContent {
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
                            model = TaskEditorModel(
                                taskId = ui.task.id,
                                priority = ui.task.priority,
                                dueDate = ui.task.dueDate,
                                dueTime = ui.task.dueTime,
                                startDate = null,
                                startTime = null,
                                project = ui.project?.id,
                                tags = remember(ui.tags) { ui.tags.map { it.id }.toImmutableList() },
                                recurrence = ui.task.recurrence,
                                isPinned = ui.task.isPinned,
                                estimateMinutes = ui.task.estimateMinutes,
                                blockedBy = ui.blockedBy,
                                followsUp = ui.followsUp,
                                workflowState = ui.task.workflowState,
                                availableTasks = ui.availableTasks,
                                // The attachments sheet reads the model's list, and the
                                // sheet is where an attachment is deleted. The detail
                                // kept its attachments out of the model — the section
                                // below the fields had them — so the sheet said "no
                                // attachments yet" on a task that had them, and there
                                // was nothing to delete from the one place that
                                // deletes.
                                attachments = ui.attachments,
                            ),
                            titleSource = ui.titleSource,
                            descriptionSource = ui.descriptionSource,
                            onTitleChange = { coordinator.onIntent(TaskDetailIntent.Domain.TitleChanged(it)) },
                            onDescriptionChange = {
                                coordinator.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it))
                            },
                            callbacks = TaskEditorCallbacks(
                                onBack = {
                                    coordinator.flushDraft()
                                    navigator.back()
                                },
                                onCheckToggle = { coordinator.onIntent(TaskDetailIntent.Domain.ToggleComplete) },
                                workflowState = RowCallbacks(
                                    onChange = {
                                        coordinator.onIntent(
                                            TaskDetailIntent.Domain.SetWorkflowState(it),
                                        )
                                    },
                                    onClick = { sheets.show(TaskEditorSheet.WorkflowState) },
                                ),
                                priority = RowCallbacks(
                                    onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Priority) },
                                    onClear = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.SetPriority(TaskPriority.None))
                                    },
                                ),
                                dueDate = DateRowCallbacks(
                                    onChangeDate = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(it)) },
                                    onChangeTime = { coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Date) },
                                    onClear = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.SetDueDate(null))
                                        coordinator.onIntent(TaskDetailIntent.Domain.SetDueTime(null))
                                    },
                                ),
                                startDate = null,
                                project = RowCallbacks(
                                    onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetProject(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Project) },
                                    onClear = { coordinator.onIntent(TaskDetailIntent.Domain.SetProject(null)) },
                                ),
                                tags = RowCallbacks(
                                    onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetTags(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Tags) },
                                    onClear = { coordinator.onIntent(TaskDetailIntent.Domain.SetTags(emptyList())) },
                                ),
                                recurrence = RowCallbacks(
                                    onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetRecurrence(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Recurrence) },
                                    onClear = { coordinator.onIntent(TaskDetailIntent.Domain.SetRecurrence(null)) },
                                ),
                                pin = ToggleCallbacks(
                                    onToggle = { coordinator.onIntent(TaskDetailIntent.Domain.TogglePinned) },
                                ),
                                estimate = RowCallbacks(
                                    onChange = { coordinator.onIntent(TaskDetailIntent.Domain.SetEstimate(it)) },
                                    onClick = { sheets.show(TaskEditorSheet.Estimate) },
                                    onClear = { coordinator.onIntent(TaskDetailIntent.Domain.SetEstimate(null)) },
                                ),
                                dependencies = RowCallbacks(
                                    onChange = {
                                        coordinator.onIntent(
                                            TaskDetailIntent.Domain.SetDependencyRelations(it),
                                        )
                                    },
                                ),
                                checklist = null,
                                // The bundle that gives the attachments sheet its
                                // behaviour: without it the sheet opens with no way to
                                // add a link or a file. A URL attachment — the kind a
                                // person types — had no way in at all.
                                attachments = AttachmentsCallbacks(
                                    onOpen = { sheets.show(TaskEditorSheet.Attachments) },
                                    onAddUrl = { url, title ->
                                        coordinator.onIntent(
                                            TaskDetailIntent.Domain.AddUrlAttachment(url, title),
                                        )
                                    },
                                    onAttachFile = { pickAttachment() },
                                    onDelete = {
                                        coordinator.onIntent(TaskDetailIntent.Domain.DeleteAttachment(it))
                                    },
                                ),
                                onTimeEntryAdd = null,
                                onTimeEntrySave = null,
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
                                            is TaskDetailExtras.Unresolved -> persistentListOf()
                                            is TaskDetailExtras.Ready -> ex.proposals
                                        }
                                        val proposalFailures = when (val ex = extras) {
                                            is TaskDetailExtras.Unresolved -> persistentMapOf()
                                            is TaskDetailExtras.Ready -> ex.proposalFailures
                                        }
                                        if (proposals.isNotEmpty()) {
                                            TaskDetailProposalSection(
                                                proposals = proposals,
                                                failures = proposalFailures,
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
                                                onEdit = { itemId, text ->
                                                    coordinator.onIntent(
                                                        TaskDetailIntent.Domain.EditProposalItem(itemId, text),
                                                    )
                                                },
                                            )
                                        }
                                        TaskDetailTagsSection(
                                            tags = ui.tags,
                                            onDeleteTag = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.RemoveTag(it),
                                                )
                                            },
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
                                            // The add-row lives in the section itself, so
                                            // a checklist item is created from the task
                                            // screen without a sheet opening.
                                            onAddItem = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.AddChecklistItem(it),
                                                )
                                            },
                                        )
                                        SubtasksSection(
                                            subtasks = ui.subtasks,
                                            onToggle = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.ToggleSubtask(it),
                                                )
                                            },
                                            onDelete = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.DeleteSubtask(it),
                                                )
                                            },
                                            onOpen = { navigator.openDetail(it.id) },
                                            onAddSubtask = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.AddSubtask(it),
                                                )
                                            },
                                        )
                                        // Always shown, not only when there is something to show: this row
                                        // is also how the user opens the attachments sheet to attach
                                        // the first file — or the first link.
                                        TaskDetailAttachmentsSection(
                                            attachments = ui.attachments,
                                            onOpenAttachments = { sheets.show(TaskEditorSheet.Attachments) },
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
                                        // The series card. Rendered only for a task that
                                        // belongs to one: a task that stands alone has
                                        // nothing to say about other occurrences.
                                        TaskSeriesCard(
                                            members = ui.seriesMembers,
                                            onOpenMember = { navigator.openDetail(it) },
                                            onApplyToAll = {
                                                coordinator.onIntent(
                                                    TaskDetailIntent.Domain.ApplyToAllOccurrences,
                                                )
                                            },
                                            onDetach = {
                                                coordinator.onIntent(TaskDetailIntent.Domain.DetachFromSeries)
                                            },
                                            onDeleteSeries = {
                                                coordinator.onIntent(TaskDetailIntent.Domain.DeleteSeries)
                                            },
                                        )
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
                                            now = now,
                                        )
                                    }
                                },
                                bottomBar = null,
                                menuItems = buildDetailMenuItems(isTrashed = ui.task.isTrashed) { intent ->
                                    coordinator.onIntent(intent)
                                },
                                onAiClick = { showAiSheet = true },
                            ),
                            isCompleted = ui.task.isCompleted,
                            now = now,
                            // The state the rows open their sheets through. The editor renders
                            // the sheets from the same state, so the row that opens one and the
                            // host that shows it are looking at one thing.
                            sheets = sheets,
                            // The detail lists its attachments under the fields, the way
                            // it lists its checklist items — so no card here. The sheet
                            // is opened from that list instead, and the callbacks the
                            // sheet needs travel with the bundle, not with the card.
                            showAttachmentsRow = false,
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

                        // A task with an unfinished prerequisite is not closed on the
                        // first request. The dialog is drawn from the state, so it
                        // survives a rotation instead of disappearing under the user's
                        // finger.
                        val pendingBlockedClose = s.pendingBlockedClose
                        if (pendingBlockedClose != null) {
                            BlockedCompletionDialog(
                                taskTitle = pendingBlockedClose.taskTitle,
                                blockerTitles = pendingBlockedClose.blockerTitles,
                                onConfirm = {
                                    coordinator.onIntent(
                                        TaskDetailIntent.Domain.ConfirmBlockedClose(pendingBlockedClose.taskId),
                                    )
                                },
                                onDismiss = { coordinator.onIntent(TaskDetailIntent.Domain.DismissBlockedClose) },
                            )
                        }
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
): ImmutableList<TaskEditorMenuItem> = if (isTrashed) {
    persistentListOf(
        TaskEditorMenuItem(
            label = "Восстановить",
            onClick = { onIntent(TaskDetailIntent.Domain.Unarchive) },
            testTag = TestTags.EditorOverflow.RESTORE,
        ),
    )
} else {
    persistentListOf(
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
