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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry

/**
 * Icon picker sheet for project icon selection.
 * Shows a grid of icons; selected icon is highlighted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPickerSheet(
    currentIcon: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Icon", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectIconRegistry.all.forEach { (key, icon) ->
                Icon(
                    icon,
                    contentDescription = key,
                    tint = if (key == currentIcon) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onPick(key) }
                        .background(
                            if (key == currentIcon) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            CircleShape,
                        )
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
