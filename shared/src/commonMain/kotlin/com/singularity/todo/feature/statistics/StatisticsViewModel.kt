package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthSummary
import com.singularity.todo.feature.tasks.domain.logic.summarizeTaskHealthFromSelectors
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import com.singularity.todo.feature.timetracking.domain.logic.OpenInterval
import com.singularity.todo.feature.timetracking.domain.logic.bucketByDay
import com.singularity.todo.feature.timetracking.domain.logic.bucketByProject
import com.singularity.todo.feature.timetracking.domain.logic.mergeIntervalsWithOpen
import com.singularity.todo.feature.timetracking.domain.logic.splitAtMidnight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

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
    /** Task-health buckets for the Health tab; null until the first computation lands. */
    val health: TaskHealthSummary? = null,
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
    private val deps: StatisticsDeps,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<StatisticsUiState, StatisticsIntent, Nothing>(
        initialState = StatisticsUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    init {
        addCloseable(scope)

        // ── Task statistics ────────────────────────────────────────────────
        scope.launch {
            deps.taskRepository.observeByFilter(TaskFilter.All)
                .map { tasks ->
                    val nowMs = deps.clock.now().toEpochMilliseconds()
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
                .catch { error ->
                    crashReporter.report(error, TASK_STATISTICS_FAILED)
                    emit(StatisticsSnapshot(emptyList(), 0, 0, 0.0, 0))
                }
                .collect { snapshot ->
                    updateState { it.copy(snapshot = snapshot, loading = false) }
                }
        }

        // ── Task health ───────────────────────────────────────────────────────
        // A separate collector rather than a step inside the one above, because that
        // one maps tasks to `(id, epochMillis)` pairs and throws the `Task` away — health
        // needs `dependsOn`, `archivedAt` and the timestamps, so it cannot be folded in
        // without widening the statistics snapshot's inputs.
        //
        // Stagnation window is read from SettingsRepository so it can be changed at runtime.
        scope.launch {
            combine(
                deps.taskRepository.observeByFilter(TaskFilter.All),
                deps.settingsRepository.stalenessThresholdDays,
            ) { tasks, stalenessDays ->
                val now = deps.clock.now()
                val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                // `maxOf(completedAt, updatedAt)` rather than `updatedAt` alone:
                // TaskCompletionSlot copies `completedAt` without bumping `updatedAt`,
                // so a task finished this morning would otherwise read as untouched
                // since whenever it was last edited.
                val lastTouched = { task: Task ->
                    listOfNotNull(task.completedAt, task.updatedAt).max()
                }
                // Selector-based computation: null stagnationWindow means "use built-in default"
                // (Selector.Stagnant uses 21.days by default). Passing a non-null Duration
                // from the settings overrides that default.
                val stagnationWindow = if (stalenessDays > 0) {
                    (stalenessDays.toLong() * 86_400_000L).milliseconds
                } else {
                    // 0 means "use default" — pass null so Selector.Stagnant uses its own 21.days.
                    null
                }
                summarizeTaskHealthFromSelectors(
                    allTasks = tasks,
                    today = today,
                    now = now,
                    stagnationWindow = stagnationWindow,
                    lastActivityOf = lastTouched,
                )
            }
                .catch { error ->
                    crashReporter.report(error, TASK_HEALTH_FAILED)
                    // Pure function — no exception expected, but report and fall back gracefully.
                    emit(TaskHealthSummary(emptyMap(), 0, null, null))
                }
                .collect { summary -> updateState { it.copy(health = summary) } }
        }

        // ── Time-tracking insights (recomputed when rangeDays changes) ────────
        scope.launch {
            state.map { it.rangeDays }.flatMapLatest { days ->
                val now = deps.clock.now()
                val zone = TimeZone.currentSystemDefault()
                val nowMs = now.toEpochMilliseconds()
                val startMs = nowMs - (days * TimeConstants.MILLIS_PER_DAY)
                val userId = deps.currentUser.current

                combine(
                    deps.timeTrackingRepo.watchEntriesInRange(startMs, nowMs),
                    deps.taskRepository.observeByFilter(TaskFilter.All),
                    deps.projectsRepo.observeProjectsWithCounts(),
                ) { entries, tasks, projects ->
                    val taskProjectMap = tasks.associate { it.id.value to it.projectId?.value }
                    val projectNameMap = projects.associate { row -> row.project.id to row.project.name }

                    val openEntry = deps.timeTrackingRepo.getOpenEntry(userId)
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
                .catch { error ->
                    crashReporter.report(error, TIME_INSIGHTS_FAILED)
                    emit(StatisticsUiState.InsightsData())
                }
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

    private companion object {
        // Machine-shaped grouping keys — these leave the device.
        const val TASK_STATISTICS_FAILED = "statistics.tasks_failed"
        const val TASK_HEALTH_FAILED = "statistics.health_failed"
        const val TIME_INSIGHTS_FAILED = "statistics.insights_failed"
    }
}
