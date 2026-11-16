@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.platformVerticalScrollbar
import com.singularity.todo.core.ui.formatDecimal
import com.singularity.todo.core.ui.formatDuration
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.text.SelectableText
import com.singularity.todo.core.ui.theme.DataSeriesColors
import com.singularity.todo.core.ui.theme.DataStatusColors
import com.singularity.todo.feature.nav.AgendaStartRoute
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.tasks.domain.logic.AttentionDestination
import com.singularity.todo.feature.tasks.domain.logic.AttentionNudge
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthBucket
import com.singularity.todo.feature.tasks.domain.logic.TaskHealthSummary
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration

private val TAB_TITLES = listOf("Tasks", "Time", "Health")

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel = koinViewModel(),
    navCallbacks: NavCallbacks? = null,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // The items the user set aside for now.
    //
    // The state lives in the screen and not in the ViewModel, because its
    // lifetime is the screen's: the contract is that a dismissal does not
    // survive leaving the screen, and a ViewModel outlives the screen — the
    // navigation keeps the Statistics entry alive while another destination is
    // on top of it, so a dismissal held there would still be there when the
    // user came back. Plain `remember` (not `rememberSaveable`) is what makes
    // the promise true: the state goes when the screen leaves the composition,
    // and it is not restored when the screen comes back.
    //
    // It changes nothing about the task or the project it names: the dismissal
    // is a statement about the shortlist, not about the work.
    var dismissedNudgeKeys by remember { mutableStateOf<ImmutableSet<String>>(persistentSetOf()) }

    val allNudges =
        (state.nudges as? AttentionNudgesState.Ready)?.nudges ?: persistentListOf()
    val visibleNudges =
        remember(allNudges, dismissedNudgeKeys) {
            allNudges.filterNot { it.key in dismissedNudgeKeys }.toImmutableList()
        }

    RefreshableContent {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Statistics",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(
                    onClick = { navCallbacks?.navigate(AppDestination.AgendaGraph(AgendaStartRoute.SavedAgendaList)) },
                    modifier = Modifier.testTag(TestTags.STATISTICS_OPEN_SAVED_VIEWS),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Saved views",
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        // A stable id per tab: the health bucket cards live on the
                        // Health tab, the screen opens on Tasks, and a flow that
                        // waits for a bucket without selecting the tab is waiting
                        // for something on the next tab.
                        modifier =
                            Modifier.testTag(
                                when (index) {
                                    0 -> TestTags.STATISTICS_TAB_TASKS
                                    1 -> TestTags.STATISTICS_TAB_TIME
                                    else -> TestTags.STATISTICS_TAB_HEALTH
                                },
                            ),
                        text = { Text(title) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    TasksTabContent(state = state)
                }

                1 -> {
                    InsightsTabContent(
                        viewModel = viewModel,
                        insightsState = state.insights,
                        rangeDays = state.rangeDays,
                    )
                }

                2 -> {
                    HealthTabContent(
                        health = state.health,
                        nudges = state.nudges,
                        allNudges = allNudges,
                        visibleNudges = visibleNudges,
                        onDismissNudge = { key ->
                            dismissedNudgeKeys = (dismissedNudgeKeys + key).toImmutableSet()
                        },
                        onBucketClick = { bucket ->
                            when (bucket) {
                                // Overdue navigates to the dedicated Overdue preset — one section,
                                // no date prefill, exact match for the bucket.
                                TaskHealthBucket.OVERDUE -> {
                                    navCallbacks?.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Overdue))
                                }

                                // BLOCKED and STAGNANT have dedicated agenda views via Selector DSL.
                                TaskHealthBucket.BLOCKED -> {
                                    navCallbacks?.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Blocked))
                                }

                                TaskHealthBucket.STAGNANT -> {
                                    navCallbacks?.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Stagnant))
                                }

                                // FINE is healthy — no drill-down target.
                                TaskHealthBucket.FINE -> { // no-op
                                }
                            }
                        },
                        onNudgeClick = { nudge ->
                            when (val destination = nudge.destination) {
                                is AttentionDestination.Task -> {
                                    navCallbacks?.navigate(
                                        AppDestination.TasksGraph(
                                            AppDestination.TasksStartRoute.Detail(destination.id),
                                        ),
                                    )
                                }

                                is AttentionDestination.Project -> {
                                    navCallbacks?.navigate(AppDestination.ProjectDetail(destination.id))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TasksTabContent(state: StatisticsUiState) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .platformVerticalScrollbar(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.loading) {
            Text("Loading…", style = MaterialTheme.typography.bodyLarge)
            return
        }

        val snapshot = state.snapshot

        // Summary cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                title = "Completed",
                value = "${snapshot?.totalCompleted ?: 0}",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Overdue",
                value = "${snapshot?.totalOverdue ?: 0}",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = "Avg/Day",
                value =
                    formatDecimal(
                        snapshot?.averagePerDay
                            ?: 0.0,
                        1,
                    ),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        val buckets = snapshot?.tasksPerDay ?: persistentListOf()

        Text(
            // The range is the screen's own selection, not however many buckets
            // happen to have arrived: a title derived from the list said "Last 7
            // days" while the user had picked 30.
            text = "Last ${state.rangeDays} days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // Bar chart — one bar per day of the selected range, so the title, the
        // scale and the labels all describe the same window.
        TasksBarChart(buckets = buckets)
    }
}

@Composable
private fun TasksBarChart(buckets: ImmutableList<DayBucket>) {
    val bars =
        remember(buckets) {
            buckets
                .map { bucket ->
                    DayBar(
                        label = LocalDate.parse(bucket.date).day.toString(),
                        // Overdue is painted from the base and completed above it: the
                        // segment the user is asked to act on starts at the baseline.
                        // The bar is scaled against the stack (DayBarChart), so the
                        // busiest day's completed segment can no longer push the amber
                        // one off the top of the chart and hide it.
                        segments =
                            listOf(
                                BarSegment(bucket.overdueCount.toFloat(), DataStatusColors.Overdue),
                                BarSegment(bucket.completedCount.toFloat(), DataStatusColors.Completed),
                            ),
                    )
                }.toImmutableList()
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (bars.isEmpty()) {
                Text(
                    text = "Nothing recorded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DayBarChart(bars = bars, chartHeight = 200.dp)
            }
        }
    }
}

// ─── Insights tab ───────────────────────────────────────────────────────────────

@Composable
private fun InsightsTabContent(
    viewModel: StatisticsViewModel,
    insightsState: StatisticsUiState.InsightsData,
    rangeDays: Int,
) {
    if (insightsState.loading) {
        Text("Loading…", style = MaterialTheme.typography.bodyLarge)
        return
    }

    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .platformVerticalScrollbar(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Total time header + range selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Last $rangeDays days",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatDuration(insightsState.totalMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Range selector chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(7, 30, 90).forEach { days ->
                val selected = rangeDays == days
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = { viewModel.onIntent(StatisticsIntent.SetRange(days)) },
                    label = { Text("${days}d") },
                )
            }
        }

        // Stacked bar chart by day
        val buckets = insightsState.dayBuckets
        if (buckets.isNotEmpty()) {
            InsightsStackedBarChart(
                buckets = buckets,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No time entries in the last $rangeDays days",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Project breakdown
        ProjectBreakdown(
            projectBuckets = insightsState.projectBuckets,
            totalMs = insightsState.totalMs,
        )
    }
}

@Composable
private fun ProjectBreakdown(
    projectBuckets: ImmutableList<ProjectInsightsBucket>,
    totalMs: Long,
) {
    if (projectBuckets.isEmpty()) return

    Text(
        text = "By project",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )

    val topProjects = projectBuckets.take(6)
    val overflow = projectBuckets.size - topProjects.size

    topProjects.forEachIndexed { index, bucket ->
        val color = DataSeriesColors.at(index)
        ProjectTimeRow(
            name = bucket.projectName,
            totalMs = bucket.totalMs,
            color = color,
            fraction = if (totalMs > 0) bucket.totalMs.toDouble() / totalMs else 0.0,
        )
    }

    if (overflow > 0) {
        Text(
            text = "+$overflow more",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightsStackedBarChart(
    buckets: ImmutableList<DayInsightsBucket>,
    modifier: Modifier = Modifier,
) {
    val bars =
        remember(buckets) {
            buckets
                .map { bucket ->
                    DayBar(
                        label = LocalDate.parse(bucket.date).day.toString(),
                        segments = listOf(BarSegment(bucket.totalMs.toFloat(), DataStatusColors.Completed)),
                    )
                }.toImmutableList()
        }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            // The same chart the Tasks tab uses: one geometry, one label rule,
            // so the day numbers sit under their bars at every range.
            DayBarChart(bars = bars, chartHeight = 180.dp)
        }
    }
}

@Composable
private fun ProjectTimeRow(
    name: String,
    totalMs: Long,
    color: Color,
    fraction: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color swatch
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(8.dp))
        SelectableText(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(totalMs),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDecimal(fraction * 100, 0) + "%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Formats milliseconds as "Xh Ym" or "Ym".
 */

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    hint: String? = null,
) {
    Card(modifier = if (testTag != null) modifier.testTag(testTag) else modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What the number means, not just what it counts. A card that says
            // "3" and "Blocked" leaves the user to work out whether that is a
            // problem to act on and where tapping goes; the hint states the rule the
            // count was made with, which is the part they cannot see.
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The Health tab: what is wrong with the open task list, as counts.
 *
 * ## Why counts and not a score
 *
 * `TaskHealth` refuses to produce one number for a reason stated there — weighting
 * overdue against blocked against stagnant is an invented judgement. Showing the buckets
 * separately keeps the reason visible, which is the part the user can act on.
 *
 * Ordering is the summary's own `actionable` list (overdue, blocked, stagnant) rather
 * than the enum's declaration order, so the most actionable problem is the first card.
 */
@Composable
private fun HealthTabContent(
    health: TaskHealthState,
    nudges: AttentionNudgesState,
    allNudges: ImmutableList<AttentionNudge>,
    visibleNudges: ImmutableList<AttentionNudge>,
    onDismissNudge: (String) -> Unit,
    onBucketClick: (TaskHealthBucket) -> Unit,
    onNudgeClick: (AttentionNudge) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .platformVerticalScrollbar(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The shortlist comes before the counts: it answers "what do I do first",
        // which is the question the counts only hint at.
        AttentionNudgesSection(
            state = nudges,
            all = allNudges,
            visible = visibleNudges,
            onDismiss = onDismissNudge,
            onClick = onNudgeClick,
        )

        // The counts and the list are two readings of the same rows, and each is
        // available on its own. A failure of one query must not take the other's
        // answer off the screen — the tab used to return early on an unavailable
        // health summary, which threw away a list that had been read
        // successfully. Each failure is reported in its own words, where it
        // happened.
        when (health) {
            TaskHealthState.Loading -> {
                // The query has not landed yet. Saying "unavailable" here showed a
                // broken-looking screen on a perfectly healthy database, for as
                // long as the first computation took.
                Text("Loading…", style = MaterialTheme.typography.bodyLarge)
            }

            TaskHealthState.Unavailable -> {
                // The query failed: the health of the task list is unknown, which
                // is not the same as "no problems".
                EmptyState(title = "Task health unavailable")
            }

            is TaskHealthState.Ready -> {
                HealthBucketCards(health.summary, onBucketClick)
            }
        }
    }
}

/**
 * The counts: how much of the open task list is late, waiting or idle.
 *
 * Split out of [HealthTabContent] so that the tab's own structure — the list, the
 * counts, and the state each of them is in — is readable at a glance.
 */
@Composable
private fun HealthBucketCards(
    summary: TaskHealthSummary,
    onBucketClick: (TaskHealthBucket) -> Unit,
) {
    StatCard("Open tasks", summary.totalOpen.toString(), Modifier.fillMaxWidth())

    summary.actionable.forEach { bucket ->
        StatCard(
            title = bucket.displayLabel(),
            value = summary[bucket].toString(),
            hint = bucket.explanation(),
            // Every bucket in `actionable` is a problem the user can open —
            // the list itself is what makes a bucket actionable, so there is
            // nothing to test here.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onBucketClick(bucket) },
            testTag = bucket.testTag(),
        )
    }

    summary.medianStalledFor?.let { stalled ->
        Text(
            text = "Median time untouched: ${stalled.formatDays()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text =
            summary.completionRate?.let { "Completion rate: ${(it * 100).toInt()}%" }
                ?: "Completion rate: nothing created yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The attention items: the few things worth doing first, each with the one reason
 * it is there and a way into it.
 *
 * The list is bounded, and it says how many items it did not show — a shortlist
 * that hides its own size reads as "that is all of it", and it is not.
 */
@Composable
private fun AttentionNudgesSection(
    state: AttentionNudgesState,
    all: ImmutableList<AttentionNudge>,
    visible: ImmutableList<AttentionNudge>,
    onDismiss: (String) -> Unit,
    onClick: (AttentionNudge) -> Unit,
) {
    when (state) {
        // The query has not landed yet, and the tab already says it is loading
        // about the counts. A second "loading" line would be noise.
        AttentionNudgesState.Loading -> {
            Unit
        }

        // The query failed: what needs attention is unknown, which is not the
        // same as "nothing needs attention". The list says so where it can be
        // seen — a list that is silently absent reads as an answer, and the
        // counts beside it would then be the only thing on the tab.
        AttentionNudgesState.Unavailable -> {
            Text(
                text = "The attention list is unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is AttentionNudgesState.Ready -> {
            AttentionNudgeRows(all, visible, onDismiss, onClick)
        }
    }
}

/**
 * The rows of the attention list, and the count of the items it did not show.
 *
 * Split out of [AttentionNudgesSection] so that the section's own structure — a
 * list that is loading, a list that failed, and a list that is ready — is
 * readable at a glance.
 */
@Composable
private fun AttentionNudgeRows(
    all: ImmutableList<AttentionNudge>,
    visible: ImmutableList<AttentionNudge>,
    onDismiss: (String) -> Unit,
    onClick: (AttentionNudge) -> Unit,
) {
    if (visible.isEmpty()) {
        // The count is unconditional: the list says how many items it did not
        // show, and the state where the user set them all aside is a state
        // where it showed none of them. A count that vanished as the list
        // emptied would be a count that only appeared when it was convenient.
        //
        // The wording does not say the items were set aside, because some of
        // them may only be below the bound — and it does not say nothing needs
        // attention, because these items do need attention. It says what is
        // true: the list is showing none of them, and there are this many.
        val hidden = all.size
        Text(
            text =
                if (hidden == 0) {
                    "Nothing needs attention right now"
                } else {
                    notShownLabel(hidden)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                if (hidden == 0) {
                    Modifier
                } else {
                    Modifier.testTag(TestTags.ATTENTION_NUDGES_MORE)
                },
        )
        return
    }

    Text(
        text = "Needs attention",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )

    val shown = visible.take(MAX_VISIBLE_NUDGES)
    shown.forEach { nudge ->
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(nudge) }
                    .testTag(TestTags.attentionNudge(nudge.key)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nudge.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = nudge.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onDismiss(nudge.key) },
                modifier = Modifier.testTag(TestTags.attentionNudgeDismiss(nudge.key)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Set aside for now",
                )
            }
        }
    }

    // The count is taken from the whole list, not from what is left of it after
    // the dismissals. An item the user set aside is still an item this list did
    // not show, and the count is about what the list did not show — the items
    // left out by the bound and the items the user set aside are both counted.
    val hidden = all.size - shown.size
    if (hidden > 0) {
        Text(
            text = "and $hidden more",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.ATTENTION_NUDGES_MORE),
        )
    }
}

/** How many attention items the Health tab shows at once. */
private const val MAX_VISIBLE_NUDGES = 5

/**
 * The sentence for the state where the list shows nothing.
 *
 * It carries the count, because the count is what the list owes the user in
 * every state, and it says "not shown" rather than "set aside", because an
 * item can be hidden by the bound rather than by the user's own hand.
 */
private fun notShownLabel(count: Int): String =
    if (count == 1) {
        "1 item is not shown right now"
    } else {
        "$count items are not shown right now"
    }

/** The card's test tag, or null for a bucket that never gets a card. */
private fun TaskHealthBucket.testTag(): String? =
    when (this) {
        TaskHealthBucket.OVERDUE -> TestTags.HEALTH_BUCKET_OVERDUE

        TaskHealthBucket.BLOCKED -> TestTags.HEALTH_BUCKET_BLOCKED

        TaskHealthBucket.STAGNANT -> TestTags.HEALTH_BUCKET_STAGNANT

        // FINE is healthy — it is not in `actionable`, so it has no card.
        TaskHealthBucket.FINE -> null
    }

private fun TaskHealthBucket.displayLabel(): String =
    when (this) {
        TaskHealthBucket.OVERDUE -> "Overdue"
        TaskHealthBucket.BLOCKED -> "Blocked"
        TaskHealthBucket.STAGNANT -> "Not touched in a while"
        TaskHealthBucket.FINE -> "On track"
    }

/**
 * The rule the count was made with, in the user's words.
 *
 * The card is a number and a label, and neither says *why* a task is in the bucket
 * or what tapping will show. The rule is the part a user cannot infer from the
 * number — "blocked" reads like a status they set, and "not touched in a while" is
 * a threshold they configured and did not see restated.
 *
 * Each line restates the *whole* predicate the bucket is counted by, and no more:
 *
 * - overdue is `dueDate < today` on an open task;
 * - blocked is an unfinished prerequisite, and only a dependency that blocks;
 * - stagnant is an undated open task idle past the threshold;
 * - fine is everything else that is open — which includes a task due next month that
 *   has not been edited in a year. It is the *absence* of the three conditions, not
 *   a claim that the task is fresh, so it must not say "recently touched": that is a
 *   narrower condition than the bucket, and a user with a dated, unedited task would
 *   be told something false about it.
 */
private fun TaskHealthBucket.explanation(): String =
    when (this) {
        TaskHealthBucket.OVERDUE -> "Past the due date and still open"
        TaskHealthBucket.BLOCKED -> "Waiting on something that is not finished"
        TaskHealthBucket.STAGNANT -> "Undated, and idle past your staleness threshold"
        TaskHealthBucket.FINE -> "No overdue, blocked or stagnation condition"
    }

/** Whole days, because a partial day is noise on a retention-scale number. */
private fun Duration.formatDays(): String {
    val d = inWholeDays
    return if (d <= 0) "under a day" else "$d day${if (d == 1L) "" else "s"}"
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun StatisticsScreenLightPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            TasksTabContent(
                state =
                    StatisticsUiState(
                        loading = false,
                        snapshot =
                            StatisticsSnapshot(
                                tasksPerDay =
                                    listOf(
                                        DayBucket("2026-08-31", 2, 0),
                                        DayBucket("2026-09-01", 5, 0),
                                        DayBucket("2026-09-02", 3, 1),
                                        DayBucket("2026-09-03", 7, 0),
                                        DayBucket("2026-09-04", 4, 0),
                                        DayBucket("2026-09-05", 6, 2),
                                        DayBucket("2026-09-06", 8, 0),
                                    ).toImmutableList(),
                                totalCompleted = 35,
                                totalOverdue = 3,
                                averagePerDay = 5.0,
                            ),
                    ),
            )
        }
    }
