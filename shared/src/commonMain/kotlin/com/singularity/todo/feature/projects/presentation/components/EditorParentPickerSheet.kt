package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * Simple parent picker for project editor — shows only "None (root)" option.
 * Full parent-project search/list selection is available in ProjectDetail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorParentPickerSheet(
    onPick: (ProjectId?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.mapTestTagsAsResourceIds(),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The sheet is modal and picking is the only way out of it otherwise,
                // so "cancel without choosing" needs a control of its own. It is the
                // same close affordance the editor sheets use, so a dismiss looks the
                // same wherever it appears.
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(TestTags.PROJECT_PICKER_DISMISS),
                ) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                }
                Spacer(Modifier.width(8.dp))
                Text("Parent project", style = MaterialTheme.typography.titleMedium)
            }
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
