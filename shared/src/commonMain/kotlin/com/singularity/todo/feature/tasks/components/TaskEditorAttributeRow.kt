package com.singularity.todo.feature.tasks.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A reusable clickable row for an attribute chip: icon + label + optional value + clear action.
 * Used for date, time, project, tags, reminder, and attachment rows.
 *
 * @param icon        Leading icon (e.g. CalendarToday, Folder).
 * @param label       Human-readable attribute label when no value is set (e.g. "Set date").
 * @param value       Currently selected value text; when non-null the row shows value instead of label.
 * @param onClick    Called when the row (excluding the clear button) is tapped.
 * @param onClear    Optional; called when the × button is tapped to clear the value.
 *                   When null, the clear button is hidden.
 * @param modifier    Standard Compose modifier.
 */
@Composable
fun TaskEditorAttributeRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value ?: label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (onClear != null && value != null) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
