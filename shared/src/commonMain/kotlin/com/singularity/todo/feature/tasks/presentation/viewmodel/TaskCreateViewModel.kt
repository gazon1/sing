package com.singularity.todo.feature.tasks.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.DraftMviViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.DraftAttachment
import com.singularity.todo.feature.tasks.presentation.state.DraftChecklistItem
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Dependencies for [TaskCreateViewModel].
 *
 * @param draftStore A [DraftStore] scoped to the current user — the caller injects the
 *   user-scoped instance so draft keys stay bare (no manual userId prefix).
 */
data class TaskCreateDeps(
    val createFromDraft: CreateTaskFromDraftUseCase,
    val logger: Logger,
    val draftStore: DraftStore,
) {
    companion object {
        /**
         * Bare draft key — the user-scoped store prepends the user prefix internally.
         *
         * The other keys of the create flow live in [TaskDraftKeys]; this one is
         * here because it is the key the editor autosaves to and clears.
         */
        const val DRAFT_KEY = TaskDraftKeys.TASK_CREATE
    }
}

/**
 * Task create screen ViewModel.
 *
 * Owns: new task draft with kind, title, description, due date, reminders, linked project.
 * Triggers: kind/title/description/due date changes, save (explicit or auto-save on navigate-away).
 * One-shot events: [TaskCreateUiEvent.Saved].
 *
 * Draft shaping + validation + persistence are delegated to [CreateTaskFromDraftUseCase]
 * so this VM stays a thin orchestrator: it owns the editor state flow, debounced draft
 * persistence, and the conversion of validation/persistence outcomes into UI events.
 * Failures from the use case surface via `state.error`, which the screen renders in a
 * snackbar via `LaunchedEffect(state.error)`.
 *
 * @see TaskCreateUiState
 * @see TaskCreateIntent
 */
class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: kotlinx.datetime.LocalDate?,
    private val draftKey: String?,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : DraftMviViewModel<TaskDraft, TaskCreateIntent, TaskCreateUiEvent>(
        initialDraft = TaskDraft(
            dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) }
                ?: DueDateOption.None,
        ),
        autosave = { draft ->
            // Always save to the generic draft key — section prefill is restored once on entry.
            deps.draftStore.save(TaskCreateDeps.DRAFT_KEY, draft, TaskDraft.serializer())
        },
        crashReporter = crashReporter,
        restore = {
            // The draft the caller named wins over the generic one: a restored
            // draft carries the prefill (title, dates) that the screen it came
            // from put there.
            val key = draftKey ?: TaskCreateDeps.DRAFT_KEY
            deps.draftStore.load(key, TaskDraft.serializer())
        },
        logger = deps.logger,
        scope = scope,
    ) {
    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) }
            ?: DueDateOption.None,
    )

    override fun validate(draft: TaskDraft): String? =
        if (draft.title.text.isBlank()) "Title is required" else null

    override suspend fun persist(draft: TaskDraft): Either<AppError, Unit> =
        when (val result = deps.createFromDraft(draft)) {
            is Either.Left -> Either.Left(result.error)
            is Either.Right -> Either.Right(Unit)
        }

    override suspend fun onSaved() {
        deps.logger.i(tag = "TaskCreateViewModel") { "task created, clearing draft" }
        // Both keys are cleared. The named one holds the draft the editor
        // restored from, and the generic one holds the autosave — which writes
        // there even when the restore came from elsewhere. Clearing only one of
        // them left a draft behind, and the next open of the editor started
        // with what the user had already saved.
        listOfNotNull(draftKey, TaskCreateDeps.DRAFT_KEY).distinct().forEach { key ->
            runCatchingCancellable { deps.draftStore.clear(key) }
                .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft clear failed" } }
        }
        emit(TaskCreateUiEvent.Saved)
    }

    override fun onAutosaveError(e: Throwable) {
        deps.logger.e(e, tag = "TaskCreate") { "autosave failed" }
    }

    override fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged ->
                // The field's own text: the field wrote it, and the field already shows it.
                updateDraft { it.copy(title = it.title.echoed(intent.title)) }

            is TaskCreateIntent.DescriptionChanged ->
                // The field's own text: the field wrote it, and the field already shows it.
                updateDraft { it.copy(description = it.description.echoed(intent.description)) }

            is TaskCreateIntent.SetPriority -> updateDraft { it.copy(priority = intent.priority) }

            is TaskCreateIntent.SetDueDate -> updateDraft {
                val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                // A single date replaces the whole set: the two intents say the same
                // thing about "the dates of this task", and mixing them would leave
                // a set whose primary date is not in it.
                it.copy(dueDate = option, extraDueDates = emptyList())
            }

            is TaskCreateIntent.SetDueDates -> setDueDates(intent.dates)

            is TaskCreateIntent.SetDueTime -> updateDraft { it.copy(dueTime = intent.time) }

            TaskCreateIntent.DueDateCleared -> updateDraft {
                it.copy(dueDate = DueDateOption.None, dueTime = null, extraDueDates = emptyList())
            }

            is TaskCreateIntent.SetStartDate -> updateDraft {
                val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                it.copy(startDate = option)
            }

            is TaskCreateIntent.SetStartTime -> updateDraft { it.copy(startTime = intent.time) }

            is TaskCreateIntent.SetEndDate -> updateDraft {
                val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                it.copy(endDate = option)
            }

            is TaskCreateIntent.SetEndTime -> updateDraft { it.copy(endTime = intent.time) }

            is TaskCreateIntent.SetProject -> updateDraft { it.copy(projectId = intent.projectId?.value) }

            is TaskCreateIntent.SetTags -> updateDraft { it.copy(tagIds = intent.tagIds.map { it.value }) }

            is TaskCreateIntent.SetRecurrence -> updateDraft { it.copy(recurrence = intent.spec) }

            TaskCreateIntent.PinToggled -> updateDraft { it.copy(isPinned = !it.isPinned) }

            is TaskCreateIntent.AddChecklistItem -> {
                val newItem = DraftChecklistItem(
                    id = com.singularity.todo.core.ids.nextId(),
                    text = intent.text,
                )
                updateDraft { it.copy(checklist = it.checklist + newItem) }
            }

            is TaskCreateIntent.ToggleChecklistItem -> updateDraft { draft ->
                draft.copy(
                    checklist = draft.checklist.map { item ->
                        if (item.id == intent.id) item.copy(isChecked = !item.isChecked) else item
                    },
                )
            }

            is TaskCreateIntent.RemoveChecklistItem -> updateDraft { draft ->
                draft.copy(checklist = draft.checklist.filter { it.id != intent.id })
            }

            is TaskCreateIntent.AddAttachmentUrl -> {
                val newAttachment = DraftAttachment(url = intent.url, title = intent.title)
                updateDraft { it.copy(attachments = it.attachments + newAttachment) }
            }

            is TaskCreateIntent.RemoveAttachmentUrl -> updateDraft { draft ->
                draft.copy(attachments = draft.attachments.filter { it.url != intent.url })
            }

            TaskCreateIntent.SaveClicked -> save()

            TaskCreateIntent.DismissError -> dismissError()

            TaskCreateIntent.Discard -> {
                vmScope.launch {
                    listOfNotNull(draftKey, TaskCreateDeps.DRAFT_KEY).distinct().forEach { key ->
                        runCatchingCancellable { deps.draftStore.clear(key) }
                            .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft clear failed" } }
                    }
                }
            }
        }
    }

    /**
     * Applies a whole set of dates.
     *
     * The earliest date becomes the primary one and the rest ride along as extra
     * dates: the primary date is what the editor shows and what "Today"/"Tomorrow"
     * would have produced, and keeping the earliest as the primary keeps the
     * series in date order everywhere it is read.
     *
     * A repeat rule and a set of dates are two different intentions, and a task
     * cannot have both: the rule generates occurrences on its own, so a row that
     * carried both would produce dates nobody picked. The rule is dropped and
     * the user is told, rather than the pick being refused — the dates are what
     * the user asked for last, and they are the more specific of the two.
     */
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
            // Set after the draft update: the update recomputes the error slot from
            // validation, so a message written before it would be overwritten.
            updateState { it.copy(error = RECURRENCE_DROPPED_MESSAGE) }
        }
    }

    private companion object {
        const val RECURRENCE_DROPPED_MESSAGE = "Повтор отменён: выбрано несколько дат"
    }
}
