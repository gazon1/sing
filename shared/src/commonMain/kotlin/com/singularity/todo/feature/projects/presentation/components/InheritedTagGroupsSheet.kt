package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.presentation.model.TagGroupOption
import com.singularity.todo.feature.tags.domain.model.TagGroupId

/**
 * Inherited tag groups picker sheet.
 * Shows all available tag groups; selected groups are inherited by the current project.
 *
 * Unlike [ParentPickerSheet] (single-select), this is a multi-select sheet where
 * ticking a group adds it to the project's inherited set and un-ticking removes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InheritedTagGroupsSheet(
    options: List<TagGroupOption>,
    currentIds: Set<TagGroupId>,
    onPick: (Set<TagGroupId>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local mutable state mirrors the user's selections before they confirm with "Done".
    var selected by remember(currentIds) { mutableStateOf(currentIds) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Inherited tag groups", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Tasks in this project will see tags from the selected groups",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            if (options.isEmpty()) {
                Text(
                    text = "No tag groups available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn {
                    items(options, key = { it.id.value }) { opt ->
                        FilterChip(
                            selected = opt.id in selected,
                            onClick = {
                                selected = if (opt.id in selected) {
                                    selected - opt.id
                                } else {
                                    selected + opt.id
                                }
                            },
                            label = { Text(opt.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(opt.color)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Confirm button — applies all selections at once
            TextButton(
                onClick = { onPick(selected) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
