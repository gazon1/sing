package com.singularity.todo.feature.statistics

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatisticsUiState(val snapshot: StatisticsSnapshot? = null, val loading: Boolean = true)

/**
 * Statistics screen ViewModel.
 *
 * Owns: [StatisticsSnapshot] — completed task counts by period, overdue count.
 * Triggers: profile switch (recomputes from new user's tasks).
 * No one-shot events — purely observational state.
 *
 * @see StatisticsUiState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    // Secondary — production Koin uses this
    constructor(
        taskRepository: TaskRepository,
        clock: Clock,
    ) : this(
        taskRepository, clock,
        scope = AutoCloseableCoroutineScope(),
    )

    val state: StateFlow<StatisticsUiState> = taskRepository.observeByFilter(TaskFilter.All)
        .map { tasks ->
            val nowMs = clock.now().toEpochMilliseconds()
            val completed = tasks.filter { it.completedAt != null }
                .map { it.id.value to it.completedAt!!.toEpochMilliseconds() }
            val overdue = tasks.filter { it.dueDate != null && it.completedAt == null }
                .map { task ->
                    val dueEpoch = task.dueDate!!.toEpochDays() * 86_400_000L
                    task.id.value to dueEpoch
                }
            StatisticsUiState(
                snapshot = computeStatistics(completed, overdue, nowMs, 7),
                loading = false,
            )
        }
        .catch { emit(StatisticsUiState(loading = false)) }
        .stateIn(scope, sharingStarted(), StatisticsUiState(loading = true))
}
