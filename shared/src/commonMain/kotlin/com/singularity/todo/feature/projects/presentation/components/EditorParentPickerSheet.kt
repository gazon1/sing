package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * Simple parent picker for project editor — shows only "None (root)" option.
 * Full parent-project search/list selection is available in ProjectDetail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorParentPickerSheet(onPick: (ProjectId?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Parent project", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { onPick(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("None (root project)")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
