package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import com.singularity.todo.feature.timetracking.domain.logic.OpenInterval
import com.singularity.todo.feature.timetracking.domain.logic.bucketByDay
import com.singularity.todo.feature.timetracking.domain.logic.bucketByProject
import com.singularity.todo.feature.timetracking.domain.logic.mergeIntervalsWithOpen
import com.singularity.todo.feature.timetracking.domain.logic.splitAtMidnight
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * ViewModel for the Insights (time-tracking) tab in the Statistics screen.
 *
 * Observes time entries and tasks over the last 7 days, groups by day and by project,
 * and renders top-N series as stacked bars.
 */
class InsightsViewModel(
    private val timeTrackingRepo: TimeTrackingRepository,
    private val taskRepository: TaskRepository,
    private val projectsRepo: ProjectsRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<InsightsUiState, InsightsIntent, Nothing>(
        initialState = InsightsUiState(),
        scope = scope,
    ) {

    init {
        vmScope.launch {
            val userId = currentUser.scopedUserId.value
            val now = clock.now()
            val zone = TimeZone.currentSystemDefault()
            val nowMs = now.toEpochMilliseconds()
            val startMs = nowMs - (7 * TimeConstants.MILLIS_PER_DAY)

            combine(
                timeTrackingRepo.watchEntriesInRange(userId, startMs, nowMs),
                taskRepository.observeByFilter(TaskFilter.All),
                projectsRepo.observeProjectsWithCounts(),
            ) { entries, tasks, projects ->
                // taskId → projectId
                val taskProjectMap = tasks.associate { it.id.value to it.projectId?.value }
                // projectId (String) → name
                val projectNameMap = projects.associate { row ->
                    row.project.id to row.project.name
                }

                // Merge + split at midnight for day bucketing
                val openEntry = timeTrackingRepo.getOpenEntry(userId)
                val openInterval = openEntry?.let { OpenInterval(it.startedAt.toEpochMilliseconds()) }
                val merged = mergeIntervalsWithOpen(entries, openInterval, nowMs)
                val split = merged.flatMap { splitAtMidnight(it, zone) }

                // Day buckets
                val dayBuckets = bucketByDay(split, nowMs, 7, zone)

                // Project buckets — use unsplit entries (project totals don't need midnight split)
                val projectBuckets = bucketByProject(entries, taskProjectMap)
                    .map { (projectId, totalMs) ->
                        val name = projectId?.let { projectNameMap[it] } ?: "No project"
                        ProjectInsightsBucket(
                            projectId = projectId,
                            projectName = name,
                            totalMs = totalMs,
                        )
                    }
                    .sortedByDescending { it.totalMs }

                InsightsUiState(
                    loading = false,
                    dayBuckets = dayBuckets,
                    projectBuckets = projectBuckets,
                    totalMs = split.sumOf { it.durationMs },
                )
            }
                .catch { updateState { InsightsUiState(loading = false) } }
                .collect { newState -> updateState { newState } }
        }
    }

    override fun onIntent(intent: InsightsIntent) {
        // Currently no user intents — purely observational
    }
}

/**
 * Sealed intent for the Insights tab. Reserved for future range/filter intents.
 */
sealed interface InsightsIntent : MviIntent

/**
 * UI state for the Insights tab.
 *
 * @property loading Whether data is still loading.
 * @property dayBuckets Time-series buckets grouped by day.
 * @property projectBuckets Top-N project buckets sorted by total time.
 * @property totalMs Total merged work time across all entries in the range.
 */
data class InsightsUiState(
    val loading: Boolean = true,
    val dayBuckets: List<DayInsightsBucket> = emptyList(),
    val projectBuckets: List<ProjectInsightsBucket> = emptyList(),
    val totalMs: Long = 0L,
)

/**
 * A single project's time total.
 *
 * @property projectId The project's ID, or null for tasks with no project.
 * @property projectName Display name for the project.
 * @property totalMs Total merged work time in milliseconds.
 */
data class ProjectInsightsBucket(val projectId: String?, val projectName: String, val totalMs: Long)
