package com.singularity.todo.feature.checklist.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Stateless checklist row — checkbox, label, optional delete and promote.
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
 * @param onPromote Optional "Convert to task" action shown in the overflow menu.
 * @param modifier  Standard Compose modifier.
 */
@Composable
fun ChecklistItemRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onPromote: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

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

        if (onDelete != null || onPromote != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (onPromote != null) {
                        DropdownMenuItem(
                            text = { Text("Convert to task") },
                            onClick = {
                                menuOpen = false
                                onPromote()
                            },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChecklistItemRowLightPreview() = PreviewThemed(darkTheme = false) {
    ChecklistItemRow(
        text = "Buy groceries",
        checked = false,
        onToggle = {},
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChecklistItemRowDoneDarkPreview() = PreviewThemed(darkTheme = true) {
    ChecklistItemRow(
        text = "Read documentation",
        checked = true,
        onToggle = {},
        onDelete = {},
    )
}
