package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.feature.settings.SettingsUiState

/**
 * Settings screen for task-related behaviour.
 *
 * Currently contains the staleness threshold: the number of days after which an
 * undated, incomplete task is considered stagnant.
 */
@Composable
fun TasksSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Task Health") {
            StalenessThresholdSlider(
                value = state.tasks.stalenessThresholdDays,
                onValueChange = { onIntent(SettingsIntent.Tasks.UpdateStalenessThresholdDays(it)) },
            )
        }
    }
}

@Composable
private fun StalenessThresholdSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minDays = 1
    val maxDays = 90
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Stale after",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "$value days",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = minDays.toFloat()..maxDays.toFloat(),
            steps = maxDays - minDays - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "An undated, incomplete task becomes stale after $value days without activity. " +
                "Used by Statistics to flag stagnant tasks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
