package com.singularity.todo.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class StatisticsUiState(
    val snapshot: StatisticsSnapshot? = null,
    val loading: Boolean = true,
)

class StatisticsViewModel(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(StatisticsUiState())
    val state: StateFlow<StatisticsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val nowMs = clock.now().toEpochMilliseconds()
            val allTasks: List<Task> = taskRepository
                .watchTasks(UserId.anonymous, TaskFilter.All)
                .first()

            val completed: List<Pair<String, Long>> = allTasks
                .filter { it.completedAt != null }
                .map { it.id.value to it.completedAt!!.toEpochMilliseconds() }

            val overdue: List<Pair<String, Long>> = allTasks
                .filter { it.dueDate != null && it.completedAt == null }
                .map { task ->
                    val dueEpoch = task.dueDate!!.toEpochDays() * 86_400_000L
                    task.id.value to dueEpoch
                }

            val stats = computeStatistics(completed, overdue, nowMs, 7)
            _state.value = StatisticsUiState(snapshot = stats, loading = false)
        }
    }
}
