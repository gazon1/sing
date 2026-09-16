package com.singularity.todo.feature.tasks.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.presentation.model.UpcomingTaskUiMapper
import com.singularity.todo.feature.tasks.presentation.state.UpcomingIntent
import com.singularity.todo.feature.tasks.presentation.state.UpcomingTaskUi
import com.singularity.todo.feature.tasks.presentation.state.UpcomingUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Calculates the Monday of the week containing [date].
 * Hardcoded to MONDAY-first week for MVP; locale configuration deferred.
 */
internal object UpcomingFirstDayOfWeek {
    fun of(date: LocalDate): LocalDate {
        // Kotlin: Monday=0, Tuesday=1, ..., Sunday=6
        // Monday of the week = date - dayOfWeek.ordinal days
        return date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingViewModel(
    private val taskRepo: TaskRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val projectRepo: ProjectsRepository,
    private val clock: Clock,
    initialDate: LocalDate,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    /** Today, stable for the lifetime of this ViewModel. */
    private val today: LocalDate = LocalDate.fromEpochDays(
        clock.now().toEpochMilliseconds() / (24L * 60 * 60 * 1000)
    )

    private val _selectedDate = MutableStateFlow(initialDate)
    private val _windowStart = MutableStateFlow(UpcomingFirstDayOfWeek.of(initialDate))

    /** Tasks for the currently selected date. */
    private val rawTasksFlow: Flow<List<UpcomingTaskUi>> = combine(
        currentUser.scopedUserId,
        _selectedDate,
    ) { uid: UserId, date: LocalDate -> uid to date }
        .flatMapLatest { pair: Pair<UserId, LocalDate> ->
            taskRepo.watchTasksByDate(pair.first, pair.second).map { list: List<Task> ->
                list.map { task: Task -> UpcomingTaskUiMapper.toUpcomingTaskUi(task, today) }
            }
        }

    /** Project names for enrichment. */
    private val projectNamesFlow: StateFlow<Map<String, String>> =
        currentUser.scopedUserId
            .flatMapLatest { uid: UserId ->
                projectRepo.watchProjects(uid)
            }.map { list: List<Project> ->
                list.associate { p: Project -> p.id.value to p.name }
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val state: StateFlow<UpcomingUiState> = combine(
        combine(rawTasksFlow, projectNamesFlow) { tasks: List<UpcomingTaskUi>, names: Map<String, String> ->
            tasks to names
        },
        _selectedDate,
        _windowStart,
    ) { pair: Pair<List<UpcomingTaskUi>, Map<String, String>>, date: LocalDate, windowStart: LocalDate ->
        val tasks = pair.first
        val names = pair.second
        val enriched = tasks.map { task: UpcomingTaskUi ->
            task.copy(projectName = names[task.projectName] ?: task.projectName)
        }
        UpcomingUiState.Content(date, windowStart, enriched)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), UpcomingUiState.Loading)

    fun onIntent(intent: UpcomingIntent) {
        when (intent) {
            is UpcomingIntent.SelectDate -> _selectedDate.value = intent.date
            UpcomingIntent.NextWeek -> _windowStart.update { it.plus(7, DateTimeUnit.DAY) }
            UpcomingIntent.PrevWeek -> _windowStart.update { it.minus(7, DateTimeUnit.DAY) }
            is UpcomingIntent.ToggleTask -> scope.launch {
                taskRepo.toggleComplete(intent.id)
            }
        }
    }
}
