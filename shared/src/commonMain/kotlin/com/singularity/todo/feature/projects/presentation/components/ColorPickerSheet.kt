package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .clickable { onPick(color) },
                ) {
                    if (color == currentColor) {
                        Icon(
                            Icons.Filled.Folder, // check icon placeholder
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
