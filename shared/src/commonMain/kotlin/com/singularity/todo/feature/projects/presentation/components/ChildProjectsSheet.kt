package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.domain.model.Project

/**
 * Sheet showing the list of child (sub-) projects.
 * Tapping a chip calls [onShowChildren] then dismisses — navigation is handled by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProjectsSheet(children: List<Project>, onShowChildren: (Project) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Sub-projects", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (children.isEmpty()) {
                Text(
                    "No sub-projects",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(children) { child ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onShowChildren(child);
                                onDismiss()
                            },
                            label = { Text(child.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
