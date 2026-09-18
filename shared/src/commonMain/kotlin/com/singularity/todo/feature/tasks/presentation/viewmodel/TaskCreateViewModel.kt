package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.ui.state.updateState
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class TaskCreateDeps(
    val createTask: CreateTaskUseCase,
    val currentUser: ProfileAwareCurrentUser,
    val logger: Logger,
    val draftStore: DraftStore,
    val autosaveScheduler: AutosaveScheduler,
) {
    companion object {
        const val DRAFT_KEY = "task_create_draft"
    }
}

/**
 * Task create screen ViewModel.
 *
 * Owns: new task draft with kind, title, description, due date, reminders, linked project.
 * Triggers: kind/title/description/due date changes, AI actions (describe, checklist, pick time,
 *   decompose), save (explicit or auto-save on navigate-away).
 * One-shot events: [TaskCreateUiEvent.NavigateBack], [TaskCreateUiEvent.ShowAiResult],
 *   [TaskCreateUiEvent.ShowError].
 *
 * @see TaskCreateUiState
 * @see TaskCreateIntent
 */
class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: LocalDate?,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        deps: TaskCreateDeps,
        initialDueDate: LocalDate?,
    ) : this(
        deps = deps,
        initialDueDate = initialDueDate,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None,
    )

    private val _draft = MutableStateFlow(initial)
    private val _isSaving = MutableStateFlow(false)

    private val _saved = Channel<Unit>(Channel.BUFFERED)

    init {
        // 1. Restore draft from DataStore — seed-if-empty pattern
        scope.launch {
            val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
            runCatching { deps.draftStore.load<TaskDraft>(key, TaskDraft.serializer()) }
                .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft restore failed: ${it.message}" } }
                .getOrNull()
                ?.let { restored ->
                    if (_draft.value == initial) _draft.value = restored
                }
        }

        // 2. Debounced silent save loop — combine with source StateFlow
        scope.launch {
            _draft.drop(1)
                .debounce { deps.autosaveScheduler.delayMs() }
                .collect { draft ->
                    val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
                    runCatching {
                        deps.draftStore.save(key, draft, TaskDraft.serializer())
                    }.onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft save failed: ${it.message}" } }
                }
        }
    }

    val saved: kotlinx.coroutines.flow.Flow<Unit> = _saved.receiveAsFlow()

    val state: StateFlow<TaskCreateUiState> = combine(
        _draft,
        _isSaving,
    ) { draft, saving ->
        val validationError: String? = validateForSave(draft)
        TaskCreateUiState(
            draft = draft,
            isSaveEnabled = validationError == null && !saving,
            error = null,
            isDirty = draft != initial,
            isSaving = saving,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TaskCreateUiState(initial, false, null, false, false))

    fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged -> with(intent) {
                _draft.updateState { it.copy(title = title) }
            }

            is TaskCreateIntent.DescriptionChanged -> with(intent) {
                _draft.updateState { it.copy(description = description) }
            }

            is TaskCreateIntent.SetPriority -> with(intent) {
                _draft.updateState { it.copy(priority = priority) }
            }

            is TaskCreateIntent.SetDueDate -> with(intent) {
                val option = date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                _draft.updateState { it.copy(dueDate = option) }
            }

            is TaskCreateIntent.SetDueTime -> with(intent) {
                _draft.updateState { it.copy(dueTime = time) }
            }

            is TaskCreateIntent.DueDateCleared -> with(intent) {
                _draft.updateState { it.copy(dueDate = DueDateOption.None, dueTime = null) }
            }

            TaskCreateIntent.SaveClicked -> {
                if (_isSaving.value) return
                scope.launch { save() }
            }

            TaskCreateIntent.DiscardChanges -> {
                _draft.value = initial
                _isSaving.value = false
                scope.launch {
                    val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
                    deps.draftStore.clear(key)
                }
            }
        }
    }

    private suspend fun save() {
        val current = _draft.value
        if (current.title.isBlank()) {
            deps.logger.d(tag = "TaskCreate") { "save skipped: title is blank, draft preserved" }
            return
        }
        _isSaving.value = true
        try {
            val userId = deps.currentUser.current
            val input = toInput(current, userId)
            when (input) {
                is Either.Left -> {
                    deps.logger.e(tag = "TaskCreateViewModel") { "validation failed: ${input.error}" }
                }

                is Either.Right -> {
                    deps.createTask(input.value)
                        .onSuccess {
                            _saved.trySend(Unit)
                            scope.launch {
                                val key = "${userId.value}:${TaskCreateDeps.DRAFT_KEY}"
                                deps.draftStore.clear(key)
                            }
                        }
                        .onFailure { e ->
                            deps.logger.e(e, tag = "TaskCreateViewModel") { "save failed: ${e.message}" }
                        }
                }
            }
        } finally {
            _isSaving.value = false
        }
    }

    private fun validateForSave(draft: TaskDraft): String? = if (draft.title.isBlank()) "Title is required" else null

    private fun toInput(draft: TaskDraft, userId: UserId): Either<String, CreateTaskInput> {
        val dueDate: LocalDate? = when (val d = draft.dueDate) {
            is DueDateOption.Custom -> d.date

            DueDateOption.Today -> com.singularity.todo.core.platform.todayInSystemZone()

            DueDateOption.Tomorrow -> {
                val today = com.singularity.todo.core.platform.todayInSystemZone()
                today.plus(1, DateTimeUnit.DAY)
            }

            DueDateOption.None -> null
        }
        val result: Either<com.singularity.todo.core.error.AppError.Validation, CreateTaskInput> =
            TaskDomain.createInput(
                title = draft.title,
                description = draft.description.ifBlank { null },
                priority = draft.priority,
                kind = TaskKind.Task,
                projectId = draft.projectId?.let { ProjectId.fromString(it) },
                parentTaskId = null,
                tagIds = draft.tagIds.map { TagId.fromString(it) },
                dueDate = dueDate,
                dueTime = draft.dueTime,
                someday = false,
                userId = userId,
            )
        return when (result) {
            is Either.Left -> Either.Left(result.error.message ?: "Validation error")
            is Either.Right -> result
        }
    }
}
