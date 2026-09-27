package com.singularity.todo.feature.tasks.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ui.DraftMviViewModel
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft

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
        /** Bare draft key — the user-scoped store prepends the user prefix internally. */
        const val DRAFT_KEY = "task_create_draft"
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
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : DraftMviViewModel<TaskDraft, TaskCreateIntent, TaskCreateUiEvent>(
        initialDraft = TaskDraft(
            dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) }
                ?: DueDateOption.None,
        ),
        autosave = { draft ->
            deps.draftStore.save(TaskCreateDeps.DRAFT_KEY, draft, TaskDraft.serializer())
        },
        restore = {
            deps.draftStore.load(TaskCreateDeps.DRAFT_KEY, TaskDraft.serializer())
        },
        logger = deps.logger,
        scope = scope,
    ) {
    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) }
            ?: DueDateOption.None,
    )

    override fun validate(draft: TaskDraft): String? = if (draft.title.isBlank()) "Title is required" else null

    override suspend fun persist(draft: TaskDraft): Either<AppError, Unit> =
        when (val result = deps.createFromDraft(draft)) {
            is Either.Left -> Either.Left(result.error)
            is Either.Right -> Either.Right(Unit)
        }

    override suspend fun onSaved() {
        deps.logger.i(tag = "TaskCreateViewModel") { "task created, clearing draft" }
        runCatching { deps.draftStore.clear(TaskCreateDeps.DRAFT_KEY) }
            .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft clear failed" } }
        emit(TaskCreateUiEvent.Saved)
    }

    override fun onAutosaveError(e: Throwable) {
        deps.logger.e(e, tag = "TaskCreate") { "autosave failed" }
    }

    override fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged -> updateDraft { it.copy(title = intent.title) }

            is TaskCreateIntent.DescriptionChanged -> updateDraft { it.copy(description = intent.description) }

            is TaskCreateIntent.SetPriority -> updateDraft { it.copy(priority = intent.priority) }

            is TaskCreateIntent.SetDueDate -> updateDraft {
                val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                it.copy(dueDate = option)
            }

            is TaskCreateIntent.SetDueTime -> updateDraft { it.copy(dueTime = intent.time) }

            TaskCreateIntent.DueDateCleared -> updateDraft { it.copy(dueDate = DueDateOption.None, dueTime = null) }

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

            is TaskCreateIntent.SetAccentColor -> updateDraft { it.copy(accentColor = intent.color) }

            is TaskCreateIntent.SetEmoji -> updateDraft { it.copy(emoji = intent.emoji) }

            TaskCreateIntent.SaveClicked -> save()

            TaskCreateIntent.DiscardChanges -> discard()

            TaskCreateIntent.DismissError -> dismissError()
        }
    }
}
