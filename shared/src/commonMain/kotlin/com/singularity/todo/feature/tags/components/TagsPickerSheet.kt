package com.singularity.todo.feature.tags.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagsPickerSheet(selectedIds: List<TagId>, onSelect: (List<TagId>) -> Unit, onDismiss: () -> Unit) {
    TaskEditorSheetHost(
        title = "Tags",
        onClose = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Tags coming soon...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
