package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.clock.AutosaveScheduler
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.state.updateState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateUiState
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskCreateDeps(
    val createFromDraft: CreateTaskFromDraftUseCase,
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
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Production constructor — Koin uses this. */
    constructor(
        deps: TaskCreateDeps,
        initialDueDate: kotlinx.datetime.LocalDate?,
    ) : this(
        deps = deps,
        initialDueDate = initialDueDate,
        scope = AutoCloseableCoroutineScope(),
    )

    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let {
            DueDateOption.Custom(it, it.toString())
        } ?: DueDateOption.None,
    )

    private val _draft = MutableStateFlow(initial)
    private val _isSaving = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    init {
        // 1. Restore draft from DataStore — seed-if-empty pattern
        scope.launch {
            val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
            runCatching { deps.draftStore.load(key, TaskDraft.serializer()) }
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

    val state: StateFlow<TaskCreateUiState> = combine(
        _draft,
        _isSaving,
        _error,
    ) { draft, saving, error ->
        val validationError: String? = validateForSave(draft)
        TaskCreateUiState(
            draft = draft,
            isSaveEnabled = validationError == null && !saving,
            error = error,
            isDirty = draft != initial,
            isSaving = saving,
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        TaskCreateUiState(initial, false, null, false, isSaving = false),
    )

    fun onIntent(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.TitleChanged -> with(intent) {
                _draft.updateState { it.copy(title = title) }
                // Clear stale validation error as soon as the user reacts to it.
                if (validateForSave(_draft.value) == null) _error.value = null
            }

            is TaskCreateIntent.DescriptionChanged -> with(intent) {
                _draft.updateState { it.copy(description = description) }
            }

            is TaskCreateIntent.SetPriority -> with(intent) {
                _draft.updateState { it.copy(priority = priority) }
            }

            is TaskCreateIntent.SetDueDate -> with(intent) {
                val option = intent.date?.let {
                    DueDateOption.Custom(it, it.toString())
                } ?: DueDateOption.None
                _draft.updateState { it.copy(dueDate = option) }
            }

            is TaskCreateIntent.SetDueTime -> with(intent) {
                _draft.updateState { it.copy(dueTime = intent.time) }
            }

            is TaskCreateIntent.DueDateCleared -> _draft.updateState {
                it.copy(dueDate = DueDateOption.None, dueTime = null)
            }

            TaskCreateIntent.SaveClicked -> {
                if (_isSaving.value) return
                scope.launch { save() }
            }

            TaskCreateIntent.DiscardChanges -> {
                _draft.value = initial
                _isSaving.value = false
                _error.value = null
                scope.launch {
                    val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
                    deps.draftStore.clear(key)
                }
            }

            // Surface accepts a "dismiss the inline error" intent from the screen.
            TaskCreateIntent.DismissError -> _error.value = null
        }
    }

    private suspend fun save() {
        val userId: UserId = deps.currentUser.current
        val draftSnapshot: TaskDraft = _draft.value

        // Client-side guard mirrors `validateForSave` so we don't even hit the
        // use case for the obviously-invalid case. The use case still validates
        // independently — this is just a UX short-circuit.
        val preset: String? = validateForSave(draftSnapshot)
        if (preset != null) {
            _error.value = preset
            return
        }

        _isSaving.value = true
        try {
            when (val result = deps.createFromDraft(draftSnapshot, userId)) {
                is Either.Left -> {
                    deps.logger.e(tag = "TaskCreateViewModel") { "save failed: ${result.error.message}" }
                    _error.value = result.error.message ?: "Could not create task"
                }

                is Either.Right -> {
                    _saved.trySend(Unit)
                    val key = "${userId.value}:${TaskCreateDeps.DRAFT_KEY}"
                    runCatching { deps.draftStore.clear(key) }
                        .onFailure { deps.logger.e(it, tag = "TaskCreate") { "draft clear failed: ${it.message}" } }
                }
            }
        } finally {
            _isSaving.value = false
        }
    }

    private fun validateForSave(draft: TaskDraft): String? =
        if (draft.title.isBlank()) "Title is required" else null
}
