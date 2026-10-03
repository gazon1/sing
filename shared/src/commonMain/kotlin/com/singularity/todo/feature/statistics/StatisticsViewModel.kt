
package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import com.singularity.todo.feature.timetracking.domain.logic.OpenInterval
import com.singularity.todo.feature.timetracking.domain.logic.bucketByDay
import com.singularity.todo.feature.timetracking.domain.logic.bucketByProject
import com.singularity.todo.feature.timetracking.domain.logic.mergeIntervalsWithOpen
import com.singularity.todo.feature.timetracking.domain.logic.splitAtMidnight
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Unified statistics and time-tracking insights state.
 *
 * @param snapshot Task completion statistics.
 * @param insights Time-tracking insights (day/project buckets).
 * @param loading Whether either computation is still loading.
 * @param rangeDays The selected time range in days (7, 30, or 90).
 */
data class StatisticsUiState(
    val snapshot: StatisticsSnapshot? = null,
    val insights: InsightsData = InsightsData(),
    val loading: Boolean = true,
    val rangeDays: Int = 7,
) {
    /** Time-tracking data for the insights tab. */
    data class InsightsData(
        val loading: Boolean = true,
        val dayBuckets: List<DayInsightsBucket> = emptyList(),
        val projectBuckets: List<ProjectInsightsBucket> = emptyList(),
        val totalMs: Long = 0L,
    )
}

/** A single project's time total. */
@Suppress("ClassSignature")
data class ProjectInsightsBucket(val projectId: String?, val projectName: String, val totalMs: Long)

sealed interface StatisticsIntent : MviIntent {
    data class SetRange(val days: Int) : StatisticsIntent
}

/**
 * Statistics screen ViewModel — unified for task stats and time-tracking insights.
 *
 * Tracks [StatisticsSnapshot] (completed/overdue task counts) and [InsightsData]
 * (time-tracking day/project buckets). Both recompute when the selected [rangeDays]
 * changes or the profile switches.
 *
 * @see StatisticsUiState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val taskRepository: TaskRepository,
    private val timeTrackingRepo: TimeTrackingRepository,
    private val projectsRepo: ProjectsRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<StatisticsUiState, StatisticsIntent, Nothing>(
        initialState = StatisticsUiState(),
        scope = scope,
    ) {

    init {
        addCloseable(scope)

        // ── Task statistics ────────────────────────────────────────────────
        scope.launch {
            taskRepository.observeByFilter(TaskFilter.All)
                .map { tasks ->
                    val nowMs = clock.now().toEpochMilliseconds()
                    val completed = tasks.mapNotNull { task ->
                        task.completedAt?.let { task.id.value to it.toEpochMilliseconds() }
                    }
                    val overdue = tasks.mapNotNull { task ->
                        task.dueDate?.takeIf { task.completedAt == null }?.let {
                            task.id.value to (it.toEpochDays() * TimeConstants.MILLIS_PER_DAY)
                        }
                    }
                    // Snapshot is always computed at 7-day range here; range is tracked
                    // in state and used by the insights side.
                    computeStatistics(completed, overdue, nowMs, 7)
                }
                .catch { emit(StatisticsSnapshot(emptyList(), 0, 0, 0.0, 0)) }
                .collect { snapshot ->
                    updateState { it.copy(snapshot = snapshot, loading = false) }
                }
        }

        // ── Time-tracking insights (recomputed when rangeDays changes) ────────
        scope.launch {
            state.map { it.rangeDays }.flatMapLatest { days ->
                val now = clock.now()
                val zone = TimeZone.currentSystemDefault()
                val nowMs = now.toEpochMilliseconds()
                val startMs = nowMs - (days * TimeConstants.MILLIS_PER_DAY)
                val userId = currentUser.current

                combine(
                    timeTrackingRepo.watchEntriesInRange(startMs, nowMs),
                    taskRepository.observeByFilter(TaskFilter.All),
                    projectsRepo.observeProjectsWithCounts(),
                ) { entries, tasks, projects ->
                    val taskProjectMap = tasks.associate { it.id.value to it.projectId?.value }
                    val projectNameMap = projects.associate { row -> row.project.id to row.project.name }

                    val openEntry = timeTrackingRepo.getOpenEntry(userId)
                    val openInterval = openEntry?.let { OpenInterval(it.startedAt.toEpochMilliseconds()) }
                    val merged = mergeIntervalsWithOpen(entries, openInterval, nowMs)
                    val split = merged.flatMap { splitAtMidnight(it, zone) }

                    val dayBuckets = bucketByDay(split, nowMs, days, zone)
                    val projectBuckets = bucketByProject(entries, taskProjectMap)
                        .map { (projectId, totalMs) ->
                            val name = projectId?.let { projectNameMap[it] } ?: "No project"
                            ProjectInsightsBucket(projectId, name, totalMs)
                        }
                        .sortedByDescending { it.totalMs }

                    StatisticsUiState.InsightsData(
                        loading = false,
                        dayBuckets = dayBuckets,
                        projectBuckets = projectBuckets,
                        totalMs = split.sumOf { it.durationMs },
                    )
                }
            }
                .catch { emit(StatisticsUiState.InsightsData()) }
                .collect { insights ->
                    updateState { it.copy(insights = insights, loading = false) }
                }
        }
    }

    override fun onIntent(intent: StatisticsIntent) {
        when (intent) {
            is StatisticsIntent.SetRange -> {
                val days = intent.days
                if (days !in listOf(7, 30, 90)) return
                updateState { it.copy(rangeDays = days, loading = true) }
            }
        }
    }
}
