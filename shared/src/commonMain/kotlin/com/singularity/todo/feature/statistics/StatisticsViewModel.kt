package com.singularity.todo.feature.statistics

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.tasks.domain.logic.AttentionNudge
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthSummary
import com.singularity.todo.feature.tasks.domain.logic.buildAttentionNudges
import com.singularity.todo.feature.tasks.domain.logic.stagnationWindowOf
import com.singularity.todo.feature.tasks.domain.logic.summarizeTaskHealthFromSelectors
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import com.singularity.todo.feature.timetracking.domain.logic.OpenInterval
import com.singularity.todo.feature.timetracking.domain.logic.bucketByDay
import com.singularity.todo.feature.timetracking.domain.logic.bucketByProject
import com.singularity.todo.feature.timetracking.domain.logic.mergeIntervalsWithOpen
import com.singularity.todo.feature.timetracking.domain.logic.splitAtMidnight
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
    /** Task-health buckets for the Health tab, and what the query behind them is doing. */
    val health: TaskHealthState = TaskHealthState.Loading,
    /** The attention items for the Health tab, and what the query behind them is doing. */
    val nudges: AttentionNudgesState = AttentionNudgesState.Loading,
    /**
     * The attention items the user set aside for now.
     *
     * Transient on purpose: it lives in this state, so it is gone when the screen
     * is left, and it changes nothing about the task or the project it names.
     */
    val dismissedNudgeKeys: ImmutableSet<String> = persistentSetOf(),
    val loading: Boolean = true,
    val rangeDays: Int = 7,
) {
    /**
     * The attention items the screen shows: the derived ones, minus the ones the
     * user set aside.
     *
     * The dismissal is applied here rather than in the query, so the derived list
     * stays the truth about the tasks and the dismissal stays a statement about
     * the shortlist.
     */
    val visibleNudges: ImmutableList<AttentionNudge>
        get() =
            (nudges as? AttentionNudgesState.Ready)
                ?.nudges
                ?.filterNot { it.key in dismissedNudgeKeys }
                ?.toImmutableList()
                ?: persistentListOf()

    /** Time-tracking data for the insights tab. */
    data class InsightsData(
        val loading: Boolean = true,
        val dayBuckets: ImmutableList<DayInsightsBucket> = persistentListOf(),
        val projectBuckets: ImmutableList<ProjectInsightsBucket> = persistentListOf(),
        val totalMs: Long = 0L,
    )
}

/**
 * The Health tab's attention items, and what the query behind them is doing.
 *
 * The three states are separate for the same reason the health buckets' are: a
 * null list cannot tell "the query has not landed yet" from "the query failed",
 * and the screen said "unavailable" for both.
 */
sealed interface AttentionNudgesState {
    /** The query has not landed yet. */
    data object Loading : AttentionNudgesState

    /** The query failed: what needs attention is unknown. */
    data object Unavailable : AttentionNudgesState

    /** The attention items, computed. */
    data class Ready(val nudges: ImmutableList<AttentionNudge>) : AttentionNudgesState
}

/**
 * The Health tab's data, and what the query behind it is doing right now.
 *
 * The three states are separate on purpose. A null summary cannot tell "the
 * query has not landed yet" from "the query failed", and the Health tab said
 * "unavailable" for both — so a healthy database flashed a broken-looking
 * screen every time the tab opened, and a real failure looked like a screen
 * that was merely still loading.
 */
sealed interface TaskHealthState {
    /** The query has not landed yet. */
    data object Loading : TaskHealthState

    /** The query failed: the health of the task list is unknown. */
    data object Unavailable : TaskHealthState

    /** The health of the task list, computed. */
    data class Ready(val summary: TaskHealthSummary) : TaskHealthState
}

/** A single project's time total. */
@Suppress("ClassSignature")
data class ProjectInsightsBucket(val projectId: String?, val projectName: String, val totalMs: Long)

sealed interface StatisticsIntent : MviIntent {
    data class SetRange(val days: Int) : StatisticsIntent

    /**
     * Sets one attention item aside for now.
     *
     * The dismissal is transient: it lives in the state, so it is gone when the
     * screen is left, and it changes nothing about the task or the project.
     */
    data class DismissNudge(val key: String) : StatisticsIntent
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

        // ── Task statistics (recomputed when rangeDays changes) ─────────────
        // The chart draws one bar per day of the selected range, so the
        // snapshot has to follow the range too: a snapshot fixed at seven days
        // put a seven-bar chart under a "Last 30 days" title.
        scope.launch {
            state
                .map { it.rangeDays }
                // The range is the *input* to this computation, so the flow that
                // drives it may only fire when the range changes. Without the
                // dedupe every state write re-subscribed the repository: the
                // recomputation changed the state, which re-subscribed the
                // recomputation — a loop that re-queried the database forever.
                .distinctUntilChanged()
                .flatMapLatest { rangeDays ->
                    retryTransient("statistics.tasks") {
                        deps.taskRepository.observeByFilter(TaskFilter.All)
                    }.map { tasks ->
                        val nowMs = deps.clock.now().toEpochMilliseconds()
                        // The statistics measure works, and a series is one work
                        // scheduled on several dates: the copies count once. The
                        // grouping happens here, before the buckets are counted, so
                        // no bucket can see two copies of the same work.
                        val rows =
                            tasks.map { task ->
                                StatisticsWorkRow(
                                    id = task.id.value,
                                    seriesId = task.seriesId?.value,
                                    completedAt = task.completedAt?.toEpochMilliseconds(),
                                    dueAt = task.dueDate?.let { it.toEpochDays() * TimeConstants.MILLIS_PER_DAY },
                                )
                            }
                        val (completed, overdue) = groupIntoWorks(rows)
                        computeStatistics(completed, overdue, nowMs, rangeDays)
                    }
                }.catch { error ->
                    // The failure crossed a channel hop, and a channel carries a *copy* of
                    // the throwable — the original is its cause, so the copy is unwrapped
                    // before it is reported.
                    val reported = error.cause ?: error
                    crashReporter.report(reported, TASK_STATISTICS_FAILED)
                    emit(StatisticsSnapshot(persistentListOf(), 0, 0, 0.0))
                }.collect { snapshot ->
                    updateState { it.copy(snapshot = snapshot, loading = false) }
                }
        }

        // ── Task health ───────────────────────────────────────────────────────
        // A separate collector rather than a step inside the one above, because that
        // one maps tasks to `(id, epochMillis)` pairs and throws the `Task` away — health
        // needs `blockedBy`, `archivedAt` and the timestamps, so it cannot be folded in
        // without widening the statistics snapshot's inputs.
        //
        // Stagnation window is read from SettingsRepository so it can be changed at runtime.
        scope.launch {
            combine(
                retryTransient("statistics.health") {
                    deps.taskRepository.observeByFilter(TaskFilter.All)
                },
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
                //
                // The translation from the stored setting is `stagnationWindowOf`, not an
                // inline millisecond multiply: the AI tools read the same setting, and the
                // two used to disagree about what the stored `0` means. One function, two
                // callers, one answer.
                val stagnationWindow = stagnationWindowOf(stalenessDays)
                // Typed nullable on purpose: the catch below emits null, and a
                // non-null zero summary is what made the screen's "Task health
                // unavailable" branch unreachable.
                val summary: TaskHealthSummary? =
                    summarizeTaskHealthFromSelectors(
                        allTasks = tasks,
                        today = today,
                        now = now,
                        stagnationWindow = stagnationWindow,
                        lastActivityOf = lastTouched,
                    )
                summary
            }.catch { error ->
                // The failure crossed a channel hop, and a channel carries a *copy* of
                // the throwable — the original is its cause, so the copy is unwrapped
                // before it is reported.
                val reported = error.cause ?: error
                crashReporter.report(reported, TASK_HEALTH_FAILED)
                // The failure is a state of its own: the Health tab says
                // "unavailable" instead of showing a healthy-looking zero.
                emit(null)
            }.collect { summary ->
                updateState {
                    it.copy(
                        health =
                            summary?.let { TaskHealthState.Ready(it) }
                                ?: TaskHealthState.Unavailable,
                    )
                }
            }
        }

        // ── Attention items ────────────────────────────────────────────────────
        // A separate collector for the same reason the health one is separate: the
        // attention items need the project list as well as the task list, and
        // folding that into the health query would make a failure of the project
        // read look like a failure of the health of the tasks.
        scope.launch {
            combine(
                retryTransient("statistics.nudges.tasks") {
                    deps.taskRepository.observeByFilter(TaskFilter.All)
                },
                retryTransient("statistics.nudges.projects") {
                    deps.projectsRepo.observeAllIncludingClosed()
                },
            ) { tasks, projects ->
                val now = deps.clock.now()
                val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                // Typed nullable on purpose: the catch below emits null, and a
                // non-null empty list is what made the screen's "unavailable"
                // branch unreachable.
                val nudges: List<AttentionNudge>? =
                    buildAttentionNudges(
                        tasks = tasks,
                        projects = projects,
                        today = today,
                        now = now,
                    )
                nudges
            }.catch { error ->
                // The failure crossed a channel hop, and a channel carries a *copy* of
                // the throwable — the original is its cause, so the copy is unwrapped
                // before it is reported.
                val reported = error.cause ?: error
                crashReporter.report(reported, ATTENTION_NUDGES_FAILED)
                emit(null)
            }.collect { nudges ->
                updateState {
                    it.copy(
                        nudges =
                            nudges?.let { list -> AttentionNudgesState.Ready(list.toImmutableList()) }
                                ?: AttentionNudgesState.Unavailable,
                    )
                }
            }
        }

        // ── Time-tracking insights (recomputed when rangeDays changes) ────────
        scope.launch {
            state
                .map { it.rangeDays }
                // Same dedupe as the task statistics above: the range is the
                // input to the computation, not the state it writes to.
                .distinctUntilChanged()
                .flatMapLatest { days ->
                    val now = deps.clock.now()
                    val zone = TimeZone.currentSystemDefault()
                    val nowMs = now.toEpochMilliseconds()
                    val startMs = nowMs - (days * TimeConstants.MILLIS_PER_DAY)
                    val userId = deps.currentUser.current

                    combine(
                        retryTransient("statistics.insights.entries") {
                            deps.timeTrackingRepo.watchEntriesInRange(startMs, nowMs)
                        },
                        retryTransient("statistics.insights.tasks") {
                            deps.taskRepository.observeByFilter(TaskFilter.All)
                        },
                        retryTransient("statistics.insights.projects") {
                            deps.projectsRepo.observeProjectsWithCounts()
                        },
                    ) { entries, tasks, projects ->
                        val taskProjectMap = tasks.associate { it.id.value to it.projectId?.value }
                        val projectNameMap = projects.associate { row -> row.project.id to row.project.name }

                        // The running entry is read here rather than as a source of
                        // the combine: it changes on its own, and a source that
                        // emits once would freeze the running session at whatever
                        // it was when the range was picked.
                        val openEntry = deps.timeTrackingRepo.getOpenEntry(userId)
                        val openInterval = openEntry?.let { OpenInterval(it.startedAt.toEpochMilliseconds()) }
                        val merged = mergeIntervalsWithOpen(entries, openInterval, nowMs)
                        val split = merged.flatMap { splitAtMidnight(it, zone) }

                        val dayBuckets = bucketByDay(split, nowMs, days, zone)
                        val projectBuckets =
                            bucketByProject(entries, taskProjectMap)
                                .map { (projectId, totalMs) ->
                                    val name = projectId?.let { projectNameMap[it] } ?: "No project"
                                    ProjectInsightsBucket(projectId, name, totalMs)
                                }.sortedByDescending { it.totalMs }

                        StatisticsUiState.InsightsData(
                            loading = false,
                            dayBuckets = dayBuckets.toImmutableList(),
                            projectBuckets = projectBuckets.toImmutableList(),
                            totalMs = split.sumOf { it.durationMs },
                        )
                    }
                }.catch { error ->
                    // The failure crossed a channel hop, and a channel carries a *copy* of
                    // the throwable — the original is its cause, so the copy is unwrapped
                    // before it is reported.
                    val reported = error.cause ?: error
                    crashReporter.report(reported, TIME_INSIGHTS_FAILED)
                    emit(StatisticsUiState.InsightsData())
                }.collect { insights ->
                    updateState { it.copy(insights = insights, loading = false) }
                }
        }
    }

    override fun onIntent(intent: StatisticsIntent) {
        when (intent) {
            is StatisticsIntent.SetRange -> {
                val days = intent.days
                if (days !in listOf(7, 30, 90)) return
                // Selecting the range that is already selected is not a change:
                // the collectors recompute on a *new* range, so asking for the
                // same one would set the loading flag for a recomputation that
                // never comes, and the screen would wait on it forever.
                if (days == currentState.rangeDays) return
                updateState { it.copy(rangeDays = days, loading = true) }
            }

            is StatisticsIntent.DismissNudge -> {
                // Setting an item aside is a statement about the shortlist, not
                // about the work: nothing here touches the task or the project.
                val key = intent.key
                if (key in currentState.dismissedNudgeKeys) return
                updateState {
                    it.copy(dismissedNudgeKeys = (it.dismissedNudgeKeys + key).toImmutableSet())
                }
            }
        }
    }

    private companion object {
        // Machine-shaped grouping keys — these leave the device.
        const val TASK_STATISTICS_FAILED = "statistics.tasks_failed"
        const val TASK_HEALTH_FAILED = "statistics.health_failed"
        const val ATTENTION_NUDGES_FAILED = "statistics.nudges_failed"
        const val TIME_INSIGHTS_FAILED = "statistics.insights_failed"
    }

    /**
     * A failed observation is not the end of the collector.
     *
     * The `catch` that follows this used to be the end of the story: one transient
     * failure — a database that was busy, a channel that dropped — and the
     * collector was gone for the lifetime of the ViewModel. The tab kept the value
     * it had, the query was never asked again, and nothing said that the data had
     * stopped being fresh.
     *
     * So the failure is retried: three tries in all, with a pause that grows. A
     * failure that survives the retries is a real one — it reaches the caller's
     * `catch`, which reports it and puts the explicit failure state on screen.
     *
     * The upstream comes as a factory, because a retry has to *ask again*: a flow
     * that was built once can only be collected again, and collecting a broken
     * flow again fails in exactly the same way.
     *
     * Only the *read* goes through here. A read that fails and is retried must not
     * drag the calculation that reads its result through the retries with it — the
     * calculation is not what failed, and running it again changes nothing.
     */
    private fun <T> retryTransient(
        label: String,
        upstream: () -> Flow<T>,
    ): Flow<T> =
        flow { emitAll(upstream()) }.retryWhen { cause, attempt ->
            // A cancellation is not a failure to retry — it is the caller saying stop.
            if (cause is CancellationException) return@retryWhen false
            // Three tries in all: the first one and two retries.
            if (attempt >= 2) return@retryWhen false
            // A failure that a retry papers over leaves no trace otherwise, and the
            // crash that happens exactly once is the one nobody ever sees.
            crashReporter.addBreadcrumb("$label retried after ${cause::class.simpleName}")
            delay(500.milliseconds * (attempt + 1).toInt())
            true
        }
}
