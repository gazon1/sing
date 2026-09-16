package com.singularity.todo.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatisticsUiState(val snapshot: StatisticsSnapshot? = null, val loading: Boolean = true)

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val taskRepository: TaskRepository,
    currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<StatisticsUiState> = currentUser.scopedUserId
        .flatMapLatest { uid ->
            taskRepository.watchTasks(uid, TaskFilter.All)
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
        }
        .catch { emit(StatisticsUiState(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatisticsUiState(loading = true))
}
