
package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class StatisticsUiState(val snapshot: StatisticsSnapshot? = null, val loading: Boolean = true)

sealed interface StatisticsIntent : MviIntent
// Currently no user-triggered intents — purely observational

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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<StatisticsUiState, StatisticsIntent, Nothing>(
        initialState = StatisticsUiState(),
        scope = scope,
    ) {

    init {
        addCloseable(scope)
        scope.launch {
            taskRepository.observeByFilter(TaskFilter.All)
                .map { tasks ->
                    val nowMs = clock.now()
                        .toEpochMilliseconds()
                    val completed = tasks.mapNotNull { task ->
                        task.completedAt?.let { completedAt ->
                            task.id.value to completedAt.toEpochMilliseconds()
                        }
                    }
                    val overdue = tasks.mapNotNull { task ->
                        task.dueDate?.takeIf { task.completedAt == null }?.let { dueDate ->
                            task.id.value to (dueDate.toEpochDays() * TimeConstants.MILLIS_PER_DAY)
                        }
                    }
                    StatisticsUiState(
                        snapshot = computeStatistics(completed, overdue, nowMs, 7),
                        loading = false,
                    )
                }
                .catch { updateState { StatisticsUiState(loading = false) } }
                .collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: StatisticsIntent) {
        // No intents yet — purely observational
    }
}
