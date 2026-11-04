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
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.components.detail.AttachmentsCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.ChecklistCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.DateRowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.RowCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskCreateEditorContent
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorCallbacks
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskEditorModel
import com.singularity.todo.feature.tasks.presentation.components.detail.TaskSaveBar
import com.singularity.todo.feature.tasks.presentation.components.detail.ToggleCallbacks
import com.singularity.todo.feature.tasks.presentation.nav.LocalTasksNavigator
import com.singularity.todo.feature.tasks.domain.state.DraftAttachment
import com.singularity.todo.feature.tasks.domain.state.DraftChecklistItem
import com.singularity.todo.feature.tasks.domain.state.DueDateOption
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.domain.state.TaskTitleValidation
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock
import kotlin.time.Instant

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
fun TaskCreateScreen(
    initialDueDate: LocalDate?,
    draftKey: String? = null,
) {
    val vm: TaskCreateViewModel = koinViewModel { parametersOf(initialDueDate, draftKey) }
    // The injected clock, not the system one. This screen hands `now` down to the
    // editor and stamps draft attachments with it, so a test that pins a clock in
    // Koin controls both. `koinInject` rather than a parameter: this is a nav
    // entry, and a parameter would have to be threaded from the graph for the
    // sake of a value the graph already provides.
    val clock: Clock = koinInject()
    val now: Instant = clock.now()
    val navigator = LocalTasksNavigator.current

    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDiscard by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val sheets = rememberDialogState<TaskEditorSheet>()

    CollectEvents(vm.events) { event ->
        if (event is com.singularity.todo.feature.tasks.domain.state.TaskCreateUiEvent.Saved) {
            isNavigatingBack = true
            navigator.back()
        }
    }

    // The title's validation message is drawn under the field, not shown as a
    // snackbar: a toast is gone before it can be read, and this is the one
    // message a person needs while they are typing.
    //
    // Two questions, two sources, and both are the ViewModel's:
    //
    // *Which* message — always `validationError`, the answer of the one rule that
    // disables the save button (`TaskTitleValidation`), never whatever string
    // happens to sit in the error slot. Those are not the same string: with a
    // blank title, setting a repeat rule and then picking several dates writes
    // "Повтор отменён: выбрано несколько дат" into the slot, because that is the
    // news about the dates. Drawing it here — in error colour, under the title —
    // would point at the wrong control, and would do it while the snackbar said
    // the same sentence.
    //
    // *Whether* any — whenever the rule says the title cannot be saved, from the
    // first frame, with no "has the draft been edited?" gate on top. The gate
    // this used to have (`state.error != null`, non-null only after an edit)
    // left exactly the screen the report is about: a fresh create screen with a
    // blank title drew a grey, disabled "Сохранить" and nothing else, so the one
    // thing the person needed to be told was the one thing not on screen. A
    // blank title is not an opinion about an edit in progress; it is a title
    // that cannot be saved, and the field says so for as long as that is true.
    // The message does not flicker on the first keystroke either — the rule is
    // re-asked of the draft on every change, so it stays until the title is not
    // blank and goes the moment it is not.
    //
    // And the save button is pressable with a blank title (`isEnabled` below):
    // the batched brief's first acceptance criterion is about a save *attempt*
    // with an empty title, and an attempt needs a button that accepts the press.
    // The attempt runs the same rule, writes the same string into the error
    // slot, and the snackbar below suppresses it because it is already under the
    // field — so pressing Save on a blank title says the same thing twice in
    // neither place. Disabling the button instead would have made the criterion
    // untestable and the grey button unexplained.
    val validationError = TaskTitleValidation.error(state.draft.title.text)
    val titleError = validationError
    LaunchedEffect(state.error, validationError) {
        val error = state.error ?: return@LaunchedEffect
        // A validation failure is already under the field, where it can be read
        // while typing; the snackbar is for what the field cannot say.
        if (error != validationError) snackbarHostState.showSnackbar(error)
    }

    val guardedBack: () -> Unit = {
        when {
            isNavigatingBack -> Unit
            state.isSaving -> Unit
            state.isDirty -> showDiscard = true
            else -> navigator.back()
        }
    }

    // The system back press goes through the same guard as the header's button.
    // Without this, the button asked "discard?" and the gesture did not: the
    // gesture popped the screen and the typed title was lost. With the keyboard
    // open, the gesture closes the keyboard first — that is what a back press is
    // for while the keyboard is up.
    ImeAwareBackHandler { guardedBack() }

    DiscardChangesDialogHost(
        visible = showDiscard,
        onDiscard = {
            showDiscard = false
            vm.onIntent(TaskCreateIntent.Discard)
            navigator.back()
        },
        onKeepEditing = { showDiscard = false },
    )

    val checklistItems = state.draft.checklist.toChecklistItems()
    val attachmentItems = state.draft.attachments.toAttachments(now)
    // The *whole* set of dates, primary date first. The row has to be able to say
    // "12 окт, 17 окт, 3 ноя" — a list without its primary date would name two
    // of the three.
    val dueDates =
        remember(state.draft.dueDate, state.draft.extraDueDates) {
            val primary = listOfNotNull((state.draft.dueDate as? DueDateOption.Custom)?.date)
            (primary + state.draft.extraDueDates).toImmutableList()
        }

    TaskCreateEditorContent(
        model =
            TaskEditorModel(
                taskId = null,
                priority = state.draft.priority,
                dueDate = (state.draft.dueDate as? DueDateOption.Custom)?.date,
                dueDates = dueDates,
                // The create editor is where several dates make sense: the work does
                // not exist yet, so there is nothing to split. Everywhere else a task
                // has one date.
                multiDateEnabled = true,
                dueTime = state.draft.dueTime,
                startDate = (state.draft.startDate as? DueDateOption.Custom)?.date,
                startTime = state.draft.startTime,
                project = state.draft.projectId?.let { ProjectId.fromString(it) },
                tags =
                    remember(state.draft.tagIds) {
                        state.draft.tagIds
                            .map { TagId.fromString(it) }
                            .toImmutableList()
                    },
                checklist = remember(checklistItems) { checklistItems.toImmutableList() },
                attachments = remember(attachmentItems) { attachmentItems.toImmutableList() },
                recurrence = state.draft.recurrence,
                isPinned = state.draft.isPinned,
                estimateMinutes = null,
                blockedBy = persistentSetOf(),
                availableTasks = persistentListOf(),
            ),
        onTitleChange = { vm.onIntent(TaskCreateIntent.TitleChanged(it)) },
        onDescriptionChange = { vm.onIntent(TaskCreateIntent.DescriptionChanged(it)) },
        callbacks =
            TaskEditorCallbacks(
                onBack = guardedBack,
                // Create mode: no task exists yet, so there is nothing to complete.
                onCheckToggle = null,
                priority =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetPriority(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Priority) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.None)) },
                    ),
                dueDate =
                    DateRowCallbacks(
                        onChangeDate = { vm.onIntent(TaskCreateIntent.SetDueDate(it)) },
                        onChangeTime = { vm.onIntent(TaskCreateIntent.SetDueTime(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Date) },
                        onClear = { vm.onIntent(TaskCreateIntent.DueDateCleared) },
                        onChangeDates = { dates -> vm.onIntent(TaskCreateIntent.SetDueDates(dates)) },
                    ),
                startDate =
                    DateRowCallbacks(
                        onChangeDate = { vm.onIntent(TaskCreateIntent.SetStartDate(it)) },
                        onChangeTime = { vm.onIntent(TaskCreateIntent.SetStartTime(it)) },
                        onClick = { sheets.show(TaskEditorSheet.StartDate) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetStartDate(null)) },
                    ),
                project =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetProject(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Project) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetProject(null)) },
                    ),
                tags =
                    RowCallbacks(
                        onChange = { ids -> vm.onIntent(TaskCreateIntent.SetTags(ids)) },
                        onClick = { sheets.show(TaskEditorSheet.Tags) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetTags(emptyList())) },
                    ),
                recurrence =
                    RowCallbacks(
                        onChange = { vm.onIntent(TaskCreateIntent.SetRecurrence(it)) },
                        onClick = { sheets.show(TaskEditorSheet.Recurrence) },
                        onClear = { vm.onIntent(TaskCreateIntent.SetRecurrence(null)) },
                    ),
                pin =
                    ToggleCallbacks(
                        onToggle = { vm.onIntent(TaskCreateIntent.PinToggled) },
                    ),
                estimate = null,
                onTimeEntryAdd = null,
                onTimeEntrySave = null,
                checklist =
                    ChecklistCallbacks(
                        onOpen = { sheets.show(TaskEditorSheet.Checklist) },
                        onAdd = { text -> vm.onIntent(TaskCreateIntent.AddChecklistItem(text)) },
                        onToggle = { id -> vm.onIntent(TaskCreateIntent.ToggleChecklistItem(id.value)) },
                        onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveChecklistItem(id.value)) },
                    ),
                attachments =
                    AttachmentsCallbacks(
                        onOpen = { sheets.show(TaskEditorSheet.Attachments) },
                        onAddUrl = { url, title -> vm.onIntent(TaskCreateIntent.AddAttachmentUrl(url, title)) },
                        // null, not an empty lambda: the task has no id until it is saved, so
                        // there is nothing to attach the file to. The sheet hides its
                        // "Add file" button rather than offering one that cannot work.
                        onAttachFile = null,
                        onDelete = { id -> vm.onIntent(TaskCreateIntent.RemoveAttachmentUrl(id.value)) },
                    ),
                dependencies = null,
                bottomBar = {
                    TaskSaveBar(
                        // Enabled while the draft is valid, and also while the only
                        // thing wrong with it is the title — that gap is the save
                        // attempt the batched brief's first criterion is about, and
                        // the field under it already says why the attempt will be
                        // refused. A button disabled by the same rule that is
                        // explained two rows above it would be a grey control with
                        // no next step; a pressable one answers the press with the
                        // same sentence, inline. `!isSaving` stays in front: a save
                        // in flight must not be tappable into a second one.
                        isEnabled = !state.isSaving && (state.isSaveEnabled || titleError != null),
                        isLoading = state.isSaving,
                        onSaveClick = { vm.onIntent(TaskCreateIntent.SaveClicked) },
                    )
                },
                menuItems = persistentListOf(),
            ),
        titleSource = state.draft.titleSource,
        descriptionSource = state.draft.descriptionSource,
        now = now,
        // What the title row draws under the field, and the host that shows
        // everything else. This screen is the whole of what the editor renders,
        // so it owns the snackbar host: a persistence failure with no host was a
        // failure nobody saw.
        titleError = titleError,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The state the rows open their sheets through. The editor renders the sheets
        // from the same state, so the row that opens one and the host that shows it are
        // looking at one thing.
        sheets = sheets,
    )
}

// ─── Helpers ────────────────────────────────────────────────────────────────────

@Composable
private fun DiscardChangesDialogHost(
    visible: Boolean,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    if (visible) {
        DiscardChangesDialog(
            onDiscard = onDiscard,
            onKeepEditing = onKeepEditing,
        )
    }
}

private fun List<DraftChecklistItem>.toChecklistItems(): List<com.singularity.todo.feature.checklist.ChecklistItem> =
    map { draft ->
        com.singularity.todo.feature.checklist.ChecklistItem(
            id = ChecklistItemId.fromString(draft.id),
            taskId = "",
            title = draft.text,
            isCompleted = draft.isChecked,
        )
    }

private fun List<DraftAttachment>.toAttachments(now: Instant): List<com.singularity.todo.core.attachments.Attachment> =
    map { draft ->
        com.singularity.todo.core.attachments.Attachment(
            id =
                com.singularity.todo.core.attachments
                    .AttachmentId(draft.url),
            taskId =
                com.singularity.todo.feature.tasks.domain.model
                    .TaskId(""),
            userId = com.singularity.todo.core.ids.UserId.anonymous,
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            url = draft.url,
            title = draft.title ?: "",
            createdAt = now,
            updatedAt = now,
        )
    }
