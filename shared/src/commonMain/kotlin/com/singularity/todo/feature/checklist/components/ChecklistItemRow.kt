package com.singularity.todo.feature.checklist.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Stateless checklist row — checkbox, label, optional delete.
 *
 * Accepts primitives (`text`, `checked`) rather than a domain type, so it stays
 * completely decoupled from any feature package. Each caller maps its own
 * domain model to these primitives.
 *
 * Replaces three near-identical inline checkbox rows inside
 * [com.singularity.todo.feature.tasks.TaskEditorScreen],
 * [com.singularity.todo.feature.checklist.ChecklistEditorSheet],
 * and [com.singularity.todo.feature.tasks.TaskDetailScreen].
 *
 * @param text      The checklist item text.
 * @param checked   Whether the item is completed (determines strike-through).
 * @param onToggle  Called when the checkbox is clicked.
 * @param onDelete  Optional delete action. When null the delete icon is hidden.
 * @param modifier  Standard Compose modifier.
 */
@Composable
fun ChecklistItemRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
            color = if (checked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete item",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
