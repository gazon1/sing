package com.singularity.todo.feature.ai.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.observability.DailyUsage
import com.singularity.todo.core.observability.ModelUsage
import com.singularity.todo.core.observability.ToolUsage
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AiUsageScreen(modifier: Modifier = Modifier) {
    val viewModel: AiUsageViewModel = koinViewModel()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    if (state.isLoading) {
        LoadingIndicator(modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── Header card ─────────────────────────────────────────────────
        item {
            UsageHeaderCard(
                totalTokens = state.totalTokens,
                totalCostMicros = state.totalCostUsdMicros,
                profileName = state.profileName,
            )
        }

        // ─── Daily usage ────────────────────────────────────────────────
        if (state.dailyUsage.isNotEmpty()) {
            item {
                Text(
                    text = "Daily Usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.dailyUsage.take(7), key = { it.date }) { daily ->
                DailyUsageRow(daily)
            }
        }

        // ─── Per-tool breakdown ─────────────────────────────────────────
        if (state.toolUsage.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By Tool",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.toolUsage, key = { it.toolName }) { tool ->
                ToolUsageRow(tool)
            }
        }

        // ─── Per-model breakdown ────────────────────────────────────────
        if (state.modelUsage.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(state.modelUsage, key = { it.modelId }) { model ->
                ModelUsageRow(model)
            }
        }

        if (state.toolUsage.isEmpty() && state.modelUsage.isEmpty()) {
            item {
                Text(
                    text = "No usage recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageHeaderCard(totalTokens: Long, totalCostMicros: Long?, profileName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = profileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = formatTokens(totalTokens),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "total tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            if (totalCostMicros != null) {
                Text(
                    text = "≈ ${formatCost(totalCostMicros)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun DailyUsageRow(daily: DailyUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = daily.date, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${formatTokens(daily.totalTokens)} tokens",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (daily.totalCostUsdMicros != null) {
            Text(
                text = formatCost(daily.totalCostUsdMicros),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolUsageRow(tool: ToolUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = tool.toolName, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${formatTokens(tool.totalTokens)} tokens",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${tool.callCount} calls",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelUsageRow(model: ModelUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = model.modelId, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${formatTokens(model.totalTokens)} tokens",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${model.callCount} calls",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Formatting helpers ───────────────────────────────────────────────────────

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatCost(micros: Long): String {
    val dollars = micros / 1_000_000.0
    return "$${"%.4f".format(dollars)}"
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AiUsageScreenPreview() = PreviewThemed {
    AiUsageScreen()
}
