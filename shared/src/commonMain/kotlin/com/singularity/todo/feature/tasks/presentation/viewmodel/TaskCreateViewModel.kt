package com.singularity.todo.feature.tasks.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiEvent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dependencies for [TaskCreateViewModel].
 *
 * @param draftStore A [UserScopedDraftStore] — caller is responsible for injecting
 *   the user-scoped wrapper so draft keys stay bare (no manual userId prefix拼接).
 */
data class TaskCreateDeps(
    val createFromDraft: CreateTaskFromDraftUseCase,
    val logger: Logger,
    val draftStore: DraftStore,
) {
    companion object {
        /** Bare draft key — [UserScopedDraftStore] prepends the user prefix internally. */
        const val DRAFT_KEY = "task_create_draft"
    }
}

/**
 * Task create screen ViewModel.
 *
 * Owns: new task draft with kind, title, description, due date, reminders, linked project.
 * Triggers: kind/title/description/due date changes, AI actions (describe, checklist, pick time,
 *   decompose), save (explicit or auto-save on navigate-away).
 * One-shot events: [TaskCreateUiEvent.Saved], [TaskCreateUiEvent.Error].
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
@OptIn(FlowPreview::class)
class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: kotlinx.datetime.LocalDate?,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TaskCreateUiState, TaskCreateIntent, TaskCreateUiEvent>(
    initialState = TaskCreateUiState(
        draft = TaskDraft(
            dueDate = initialDueDate?.let {
                DueDateOption.Custom(it, it.toString())
            }
                ?: DueDateOption.None,
        ),
        isSaveEnabled = false,
        error = null,
        isDirty = false,
        isSaving = false,
    ),
    scope = scope,
) {

    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let {
            DueDateOption.Custom(it, it.toString())
        }
            ?: DueDateOption.None,
    )

    private val _draft = MutableStateFlow(initial)
    private val _isSaving = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    /** One-shot "Saved" pulse — triggers navigation back in the screen. */
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()



    init {
        // 1. Restore draft from DataStore — seed-if-empty pattern.
        // Key is bare (no userId prefix) — UserScopedDraftStore handles isolation.
        scope.launch {
            runCatching { deps.draftStore.load(TaskCreateDeps.DRAFT_KEY, TaskDraft.serializer()) }.onFailure {
                    deps.logger.e(
                        it,
                        tag = "TaskCreate"
                    ) { "draft restore failed: ${it.message}" }
                }
                .getOrNull()
                ?.let { restored ->
                    if (_draft.value == initial) _draft.value = restored
                }
        }

        // 2. Debounced silent save loop — combine with source StateFlow
        scope.launch {
            _draft.drop(1)
                .debounce { 500L }
                .collect { draft ->
                    runCatching {
                        deps.draftStore.save(TaskCreateDeps.DRAFT_KEY, draft, TaskDraft.serializer())
                    }.onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft save failed: ${it.message}" } }
                }
        }
    }

    init {
        // 3. Combine draft + saving + error into framework's _state
        scope.launch {
            combine(_draft, _isSaving, _error) { draft, saving, error ->
                val validationError: String? = validateForSave(draft)
                TaskCreateUiState(
                    draft = draft,
                    isSaveEnabled = validationError == null && !saving,
                    error = error,
                    isDirty = draft != initial,
                    isSaving = saving,
                )
            }.collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged -> with(intent) {
                _draft.update { it.copy(title = title) }
                // Clear stale validation error as soon as the user reacts to it.
                if (validateForSave(_draft.value) == null) _error.value = null
            }

            is TaskCreateIntent.DescriptionChanged -> with(intent) {
                _draft.update { it.copy(description = description) }
            }

            is TaskCreateIntent.SetPriority -> with(intent) {
                _draft.update { it.copy(priority = priority) }
            }

            is TaskCreateIntent.SetDueDate -> with(intent) {
                val option = intent.date?.let {
                    DueDateOption.Custom(it, it.toString())
                }
                    ?: DueDateOption.None
                _draft.update { it.copy(dueDate = option) }
            }

            is TaskCreateIntent.SetDueTime -> with(intent) {
                _draft.update { it.copy(dueTime = intent.time) }
            }

            TaskCreateIntent.DueDateCleared -> _draft.update {
                it.copy(dueDate = DueDateOption.None, dueTime = null)
            }

            is TaskCreateIntent.SetStartDate -> with(intent) {
                val option = intent.date?.let {
                    DueDateOption.Custom(it, it.toString())
                }
                    ?: DueDateOption.None
                _draft.update { it.copy(startDate = option) }
            }

            is TaskCreateIntent.SetStartTime -> with(intent) {
                _draft.update { it.copy(startTime = intent.time) }
            }

            is TaskCreateIntent.SetEndDate -> with(intent) {
                val option = intent.date?.let {
                    DueDateOption.Custom(it, it.toString())
                }
                    ?: DueDateOption.None
                _draft.update { it.copy(endDate = option) }
            }

            is TaskCreateIntent.SetEndTime -> with(intent) {
                _draft.update { it.copy(endTime = intent.time) }
            }

            is TaskCreateIntent.SetAccentColor -> with(intent) {
                _draft.update { it.copy(accentColor = intent.color) }
            }

            is TaskCreateIntent.SetEmoji -> with(intent) {
                _draft.update { it.copy(emoji = intent.emoji) }
            }

            TaskCreateIntent.SaveClicked -> {
                if (!_isSaving.compareAndSet(expect = false, update = true)) return
                scope.launch { save() }
            }

            TaskCreateIntent.DiscardChanges -> {
                _draft.value = initial
                _isSaving.value = false
                _error.value = null
                scope.launch {
                    deps.draftStore.clear(TaskCreateDeps.DRAFT_KEY)
                }
            }

            // Surface accepts a "dismiss the inline error" intent from the screen.
            TaskCreateIntent.DismissError -> _error.value = null
        }
    }

    private suspend fun save() {
        val draftSnapshot: TaskDraft = _draft.value

        // Client-side guard mirrors `validateForSave` so we don't even hit the
        // use case for the obviously-invalid case. The use case still validates
        // independently — this is just a UX short-circuit.
        val preset: String? = validateForSave(draftSnapshot)
        if (preset != null) {
            _error.value = preset
            _isSaving.value = false
            return
        }

        try {
            when (val result = deps.createFromDraft(draftSnapshot)) {
                is Either.Left -> {
                    deps.logger.e(tag = "TaskCreateViewModel") { "save failed: ${result.error.message}" }
                    _error.value = result.error.toMessage("Could not create task")
                }

                is Either.Right -> {
                    _saved.emit(Unit)
                    emit(TaskCreateUiEvent.Saved)
                    runCatching { deps.draftStore.clear(TaskCreateDeps.DRAFT_KEY) }.onFailure {
                            deps.logger.e(
                                it,
                                tag = "TaskCreate"
                            ) { "draft clear failed: ${it.message}" }
                        }
                }
            }
        } finally {
            _isSaving.value = false
        }
    }

    private fun validateForSave(draft: TaskDraft): String? =
        if (draft.title.isBlank()) "Title is required" else null
}
