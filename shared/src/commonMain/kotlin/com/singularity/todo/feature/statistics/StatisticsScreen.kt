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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.preview.PreviewThemed
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import kotlin.time.Instant

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

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
        val maxCount = buckets.maxOfOrNull { it.completedCount } ?: 1

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
                    val barHeight = if (maxCount > 0) {
                        (bucket.completedCount.toFloat() / maxCount) * chartHeight
                    } else {
                        0f
                    }

                    drawRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(index * (barWidth + spacing), chartHeight - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
            }
        }

        // Day labels
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
}

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

@Composable
private fun StatisticsContentPreview(state: StatisticsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Statistics",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (state.loading) {
            Text("Loading...", style = MaterialTheme.typography.bodyLarge)
            return
        }

        val snapshot = state.snapshot

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

        val buckets = snapshot?.tasksPerDay ?: emptyList()
        val maxCount = buckets.maxOfOrNull { it.completedCount } ?: 1

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
                    val barHeight = if (maxCount > 0) {
                        (bucket.completedCount.toFloat() / maxCount) * chartHeight
                    } else {
                        0f
                    }

                    drawRect(
                        color = Color(0xFF4CAF50),
                        topLeft = Offset(index * (barWidth + spacing), chartHeight - barHeight),
                        size = Size(barWidth, barHeight),
                    )
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
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun StatisticsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    StatisticsContentPreview(
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

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun StatisticsScreenLoadingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    StatisticsContentPreview(state = StatisticsUiState(loading = true))
}
