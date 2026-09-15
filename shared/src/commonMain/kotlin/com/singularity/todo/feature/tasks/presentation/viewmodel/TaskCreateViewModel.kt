package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.ProjectId
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: LocalDate?,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
    )

    private val _draft = MutableStateFlow(initial)
    private val _isSaving = MutableStateFlow(false)

    private val _saved = Channel<Unit>(Channel.BUFFERED)

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
            is TaskCreateIntent.TitleChanged -> {
                _draft.value = _draft.value.copy(title = intent.title)
            }
            is TaskCreateIntent.DescriptionChanged -> {
                _draft.value = _draft.value.copy(description = intent.description)
            }
            is TaskCreateIntent.SetPriority -> {
                _draft.value = _draft.value.copy(priority = intent.priority)
            }
            is TaskCreateIntent.SetDueDate -> {
                val option = intent.date?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
                _draft.value = _draft.value.copy(dueDate = option)
            }
            is TaskCreateIntent.SetDueTime -> {
                _draft.value = _draft.value.copy(dueTime = intent.time)
            }
            is TaskCreateIntent.DueDateCleared -> {
                _draft.value = _draft.value.copy(dueDate = DueDateOption.None, dueTime = null)
            }
            TaskCreateIntent.SaveClicked -> {
                if (_isSaving.value) return
                scope.launch { save() }
            }
            TaskCreateIntent.DiscardChanges -> {
                _draft.value = initial
                _isSaving.value = false
            }
        }
    }

    private suspend fun save() {
        _isSaving.value = true
        try {
            val current = _draft.value
            val userId = deps.currentUser.current

            val input = toInput(current, userId)
            when (input) {
                is Either.Left -> {
                    deps.logger.e("TaskCreateViewModel") { "validation failed: ${input.error}" }
                }
                is Either.Right -> {
                    deps.createTask(input.value)
                        .onSuccess {
                            _saved.trySend(Unit)
                        }
                        .onFailure { e ->
                            deps.logger.e("TaskCreateViewModel") { "save failed: $e" }
                        }
                }
            }
        } finally {
            _isSaving.value = false
        }
    }

    private fun validateForSave(draft: TaskDraft): String? {
        return if (draft.title.isBlank()) "Title is required" else null
    }

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
