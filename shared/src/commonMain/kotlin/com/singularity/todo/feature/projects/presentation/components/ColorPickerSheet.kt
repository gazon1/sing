package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.ColorSwatch
import com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette
/**
 * Color picker sheet for project color selection.
 * Shows a grid of color circles; selected color shows a checkmark overlay.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(currentColor: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Color", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectColorPalette.all.forEach { color ->
                ColorSwatch(
                    color = Color(color),
                    name = ProjectColorPalette.nameOf(color),
                    selected = color == currentColor,
                    onClick = { onPick(color) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
