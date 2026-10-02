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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.model.ParentOption

/**
 * Parent project picker sheet.
 * Shows "None (root)" option + list of available parent projects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPickerSheet(
    options: List<ParentOption>,
    currentParentId: ProjectId?,
    onPick: (ProjectId?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Parent project", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { onPick(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("None (root)")
            }
            LazyColumn {
                items(options, key = { it.id }) { opt ->
                    FilterChip(
                        selected = opt.isCurrent,
                        onClick = { onPick(opt.id) },
                        label = { Text(opt.name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
