@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.formatDuration
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.timetracking.domain.logic.DayInsightsBucket
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

private val TAB_TITLES = listOf("Tasks", "Time")

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            TAB_TITLES.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (selectedTab) {
            0 -> TasksTabContent(state = state)
            1 -> InsightsTabContent(viewModel = viewModel, insightsState = state.insights, rangeDays = state.rangeDays)
        }
    }
}

@Composable
private fun TasksTabContent(state: StatisticsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
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
                value = "%.1f".format(snapshot?.averagePerDay ?: 0.0),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Last 7 days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // Bar chart
        val buckets = snapshot?.tasksPerDay ?: emptyList()
        TasksBarChart(buckets = buckets)
    }
}

@Composable
private fun TasksBarChart(buckets: List<DayBucket>) {
    val maxCount = (buckets.maxOfOrNull { it.completedCount } ?: 1)
        .coerceAtLeast(buckets.maxOfOrNull { it.overdueCount } ?: 1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp),
        ) {
            val barCount = buckets.size
            if (barCount == 0) return@Canvas

            val spacing = 8.dp.toPx()
            val availableWidth = size.width - (spacing * (barCount - 1))
            val barWidth = availableWidth / barCount
            val chartHeight = size.height

            buckets.forEachIndexed { index, bucket ->
                if (maxCount > 0) {
                    // Completed: green bar from bottom
                    val completedHeight = (bucket.completedCount.toFloat() / maxCount) * chartHeight
                    drawRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(index * (barWidth + spacing), chartHeight - completedHeight),
                        size = Size(barWidth, completedHeight),
                    )

                    // Overdue: amber bar below completed
                    val overdueHeight = (bucket.overdueCount.toFloat() / maxCount) * chartHeight
                    if (overdueHeight > 0) {
                        drawRect(
                            color = Color(0xFFFF9800),
                            topLeft = Offset(
                                index * (barWidth + spacing),
                                chartHeight - completedHeight - overdueHeight,
                            ),
                            size = Size(barWidth, overdueHeight),
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        buckets.takeLast(7).forEach { bucket ->
            val day = LocalDate.parse(bucket.date).dayOfMonth.toString()
            Text(
                text = day,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Insights tab ───────────────────────────────────────────────────────────────

private val INSIGHTS_COLORS = listOf(
    Color(0xFF4CAF50), // green
    Color(0xFF2196F3), // blue
    Color(0xFFFF9800), // orange
    Color(0xFF9C27B0), // purple
    Color(0xFFE91E63), // pink
    Color(0xFF00BCD4), // cyan
)

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
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
    projectBuckets: List<ProjectInsightsBucket>,
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
        val color = INSIGHTS_COLORS[index % INSIGHTS_COLORS.size]
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
private fun InsightsStackedBarChart(buckets: List<DayInsightsBucket>, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            ) {
                val barCount = buckets.size
                if (barCount == 0) return@Canvas

                val spacing = 6.dp.toPx()
                val availableWidth = size.width - (spacing * (barCount - 1))
                val barWidth = availableWidth / barCount
                val chartHeight = size.height

                // Determine max for scaling
                val maxMs = buckets.maxOfOrNull { it.totalMs } ?: 1L

                buckets.forEachIndexed { index, bucket ->
                    val normalizedHeight = if (maxMs > 0) {
                        (bucket.totalMs.toFloat() / maxMs) * chartHeight
                    } else {
                        0f
                    }

                    drawRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(index * (barWidth + spacing), chartHeight - normalizedHeight),
                        size = Size(barWidth, normalizedHeight),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Day labels
            Row(modifier = Modifier.fillMaxWidth()) {
                buckets.forEach { bucket ->
                    val date = LocalDate.parse(bucket.date)
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color swatch
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(8.dp))
        Text(
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
            text = "%.0f%%".format(fraction * 100),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Formats milliseconds as "Xh Ym" or "Ym".
 */

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
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
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun StatisticsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        TasksTabContent(
            state = StatisticsUiState(
                loading = false,
                snapshot = StatisticsSnapshot(
                    tasksPerDay = listOf(
                        DayBucket("2026-08-31", 2, 0),
                        DayBucket("2026-09-01", 5, 0),
                        DayBucket("2026-09-02", 3, 1),
                        DayBucket("2026-09-03", 7, 0),
                        DayBucket("2026-09-04", 4, 0),
                        DayBucket("2026-09-05", 6, 2),
                        DayBucket("2026-09-06", 8, 0),
                    ),
                    totalCompleted = 35,
                    totalOverdue = 3,
                    averagePerDay = 5.0,
                    computedAt = Instant.fromEpochMilliseconds(0).toEpochMilliseconds(),
                ),
            ),
        )
    }
}
