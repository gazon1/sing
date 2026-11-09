package com.singularity.todo.feature.tasks.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.DraftMviViewModel
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.state.DraftAttachment
import com.singularity.todo.feature.tasks.domain.state.DraftChecklistItem
import com.singularity.todo.feature.tasks.domain.state.DueDateOption
import com.singularity.todo.feature.tasks.domain.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.domain.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.feature.tasks.domain.state.TaskTitleValidation
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraft
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class TaskCreateDeps(
    val createFromDraft: CreateTaskFromDraft,
    val logger: Logger,
    val draftStore: DraftStore,
) {
    companion object {
        const val DRAFT_KEY = TaskDraftKeys.TASK_CREATE
    }
}

class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: LocalDate?,
    private val draftKey: String?,
    initialProjectId: ProjectId? = null,
    initialTagIds: Set<TagId> = emptySet(),
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : DraftMviViewModel<TaskDraft, TaskCreateIntent, TaskCreateUiEvent>(
        initialDraft = taskDraftSeed(initialDueDate, initialProjectId, initialTagIds),
        autosave = { draft ->
            deps.draftStore.save(
                taskDraftStorageKey(draftKey, initialProjectId, initialTagIds),
                draft,
                TaskDraft.serializer(),
            )
        },
        crashReporter = crashReporter,
        restore = {
            deps.draftStore.load(
                taskDraftStorageKey(draftKey, initialProjectId, initialTagIds),
                TaskDraft.serializer(),
            )
        },
        logger = deps.logger,
        scope = scope,
    ) {
    private val storageKey = taskDraftStorageKey(draftKey, initialProjectId, initialTagIds)

    override fun validate(draft: TaskDraft): String? = TaskTitleValidation.error(draft.title.text)

    override suspend fun persist(draft: TaskDraft): Either<AppError, Unit> =
        when (val result = deps.createFromDraft(draft)) {
            is Either.Left -> Either.Left(result.error)
            is Either.Right -> Either.Right(Unit)
        }

    override suspend fun onSaved() {
        deps.logger.i(tag = "TaskCreateViewModel") { "task created, clearing draft" }
        clearDrafts()
        emit(TaskCreateUiEvent.Saved)
    }

    override fun onAutosaveError(e: Throwable) {
        deps.logger.e(e, tag = "TaskCreate") { "autosave failed" }
    }

    override fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged -> updateDraft { it.copy(title = it.title.echoed(intent.title)) }
            is TaskCreateIntent.DescriptionChanged -> updateDraft { it.copy(description = it.description.echoed(intent.description)) }
            is TaskCreateIntent.SetPriority -> updateDraft { it.copy(priority = intent.priority) }
            is TaskCreateIntent.SetDueDate -> {
                updateDraft {
                    val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                    it.copy(dueDate = option, extraDueDates = emptyList())
                }
            }
            is TaskCreateIntent.SetDueDates -> setDueDates(intent.dates)
            is TaskCreateIntent.SetDueTime -> updateDraft { it.copy(dueTime = intent.time) }
            TaskCreateIntent.DueDateCleared -> updateDraft { it.copy(dueDate = DueDateOption.None, dueTime = null, extraDueDates = emptyList()) }
            is TaskCreateIntent.SetStartDate -> {
                updateDraft {
                    val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                    it.copy(startDate = option)
                }
            }
            is TaskCreateIntent.SetStartTime -> updateDraft { it.copy(startTime = intent.time) }
            is TaskCreateIntent.SetEndDate -> {
                updateDraft {
                    val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                    it.copy(endDate = option)
                }
            }
            is TaskCreateIntent.SetEndTime -> updateDraft { it.copy(endTime = intent.time) }
            is TaskCreateIntent.SetProject -> updateDraft { it.copy(projectId = intent.projectId?.value) }
            is TaskCreateIntent.SetTags -> updateDraft { it.copy(tagIds = intent.tagIds.map { it.value }) }
            is TaskCreateIntent.SetRecurrence -> updateDraft { it.copy(recurrence = intent.spec) }
            TaskCreateIntent.PinToggled -> updateDraft { it.copy(isPinned = !it.isPinned) }
            is TaskCreateIntent.AddChecklistItem -> {
                val newItem =
                    DraftChecklistItem(
                        id =
                            com.singularity.todo.core.ids
                                .nextId(),
                        text = intent.text,
                    )
                updateDraft { it.copy(checklist = it.checklist + newItem) }
            }
            is TaskCreateIntent.ToggleChecklistItem -> {
                updateDraft { draft ->
                    draft.copy(
                        checklist =
                            draft.checklist.map { item ->
                                if (item.id == intent.id) item.copy(isChecked = !item.isChecked) else item
                            },
                    )
                }
            }
            is TaskCreateIntent.RemoveChecklistItem -> updateDraft { draft -> draft.copy(checklist = draft.checklist.filter { it.id != intent.id }) }
            is TaskCreateIntent.AddAttachmentUrl -> {
                val newAttachment = DraftAttachment(url = intent.url, title = intent.title)
                updateDraft { it.copy(attachments = it.attachments + newAttachment) }
            }
            is TaskCreateIntent.RemoveAttachmentUrl -> updateDraft { draft -> draft.copy(attachments = draft.attachments.filter { it.url != intent.url }) }
            TaskCreateIntent.SaveClicked -> save()
            TaskCreateIntent.DismissError -> dismissError()
            TaskCreateIntent.Discard -> vmScope.launch { clearDrafts() }
        }
    }

    private suspend fun clearDrafts() {
        listOfNotNull(storageKey, draftKey, TaskCreateDeps.DRAFT_KEY).distinct().forEach { key ->
            runCatchingCancellable { deps.draftStore.clear(key) }
                .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft clear failed" } }
        }
    }

    private fun setDueDates(dates: Set<LocalDate>) {
        if (dates.isEmpty()) {
            updateDraft { it.copy(dueDate = DueDateOption.None, extraDueDates = emptyList()) }
            return
        }
        val sorted = dates.sorted()
        val primary = sorted.first()
        val extras = sorted.drop(1)
        val dropsRecurrence = extras.isNotEmpty() && draft.recurrence != null
        updateDraft {
            it.copy(
                dueDate = DueDateOption.Custom(primary, primary.toString()),
                extraDueDates = extras,
                recurrence = if (extras.isNotEmpty()) null else it.recurrence,
            )
        }
        if (dropsRecurrence) {
            updateState { it.copy(error = RECURRENCE_DROPPED_MESSAGE) }
        }
    }

    private companion object {
        const val RECURRENCE_DROPPED_MESSAGE = "Повтор отменён: выбрано несколько дат"
    }
}

/**
 * The draft a fresh create editor starts from.
 *
 * The due date is the prefill the tab that opened the screen asked for; the
 * project and tags are the scope of the list it was opened from. All three are
 * seeds: a restored draft wins over this one, because it carries what the person
 * was already writing.
 */
private fun taskDraftSeed(
    initialDueDate: LocalDate?,
    initialProjectId: ProjectId?,
    initialTagIds: Set<TagId>,
): TaskDraft =
    TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None,
        projectId = initialProjectId?.value,
        tagIds = initialTagIds.map { it.value },
    )


internal fun taskDraftStorageKey(
    draftKey: String?,
    initialProjectId: ProjectId?,
    initialTagIds: Set<TagId>,
): String =
    buildString {
        append(draftKey ?: TaskCreateDeps.DRAFT_KEY)
        initialProjectId?.let { append(":project:").append(it.value) }
        if (initialTagIds.isNotEmpty()) {
            append(":tags:").append(initialTagIds.map { it.value }.sorted().joinToString(","))
        }
    }
