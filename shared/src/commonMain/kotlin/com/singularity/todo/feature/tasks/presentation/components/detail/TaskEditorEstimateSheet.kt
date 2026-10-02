package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Estimate selection sheet with preset chips (5/15/30/60/120 min) and a custom input field.
 *
 * @param selected Current estimate in minutes, or `null` if not set.
 * @param onSelect Called with the selected estimate in minutes, or `null` to clear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorEstimateSheet(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(5, 15, 30, 60, 120)
    var customText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(TaskSpacing.md),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { minutes ->
                FilterChip(
                    selected = selected == minutes,
                    onClick = { onSelect(minutes) },
                    label = { Text(formatEstimate(minutes)) },
                )
            }
            // Clear chip
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("None") },
            )
        }

        // Custom input
        OutlinedTextField(
            value = customText,
            onValueChange = { value ->
                customText = value.filter { it.isDigit() }
            },
            label = { Text("Custom (minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (customText.isNotEmpty()) {
            val customMinutes = customText.toIntOrNull()
            if (customMinutes != null && customMinutes > 0) {
                androidx.compose.material3.TextButton(
                    onClick = { onSelect(customMinutes) },
                ) {
                    Text("Set $customMinutes min")
                }
            }
        }
    }
}
