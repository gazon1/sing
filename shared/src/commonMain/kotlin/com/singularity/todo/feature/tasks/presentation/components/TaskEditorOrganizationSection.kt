package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Project + tag chips row. Each chip is tappable and opens the respective picker.
 */
@Composable
fun TaskEditorOrganizationSection(
    projectLabel: String?,
    tagLabels: List<String>,
    onProjectClick: () -> Unit,
    onTagsClick: () -> Unit,
    onClearProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Project chip
        if (projectLabel != null) {
            FilterChip(
                selected = true,
                onClick = onProjectClick,
                label = { Text(projectLabel) },
                leadingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                },
            )
        } else {
            FilterChip(
                selected = false,
                onClick = onProjectClick,
                label = { Text("No project", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                },
            )
        }

        // Tag chips
        if (tagLabels.isNotEmpty()) {
            tagLabels.forEach { tagLabel ->
                FilterChip(
                    selected = true,
                    onClick = onTagsClick,
                    label = { Text(tagLabel) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
                    },
                )
            }
        } else {
            FilterChip(
                selected = false,
                onClick = onTagsClick,
                label = { Text("Add tags", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
                },
            )
        }
    }
}
