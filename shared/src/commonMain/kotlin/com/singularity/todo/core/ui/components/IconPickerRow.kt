package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tappable row that shows an icon and a label.
 *
 * Used for picker rows inside editors (e.g. "Due date", "Priority", "Reminder").
 * The [icon] slot renders the icon **with its own tint** — the caller decides
 * whether to use `primary` (hasValue=true) or `onSurfaceVariant` (hasValue=false).
 * This widget only applies the correct color to the [label] text.
 *
 * Replaces four near-identical inline `Row { Icon(tint=...); Spacer; Text }.clickable { ... }`
 * blocks inside [com.singularity.todo.feature.tasks.TaskEditorScreen].
 *
 * @param icon     Composable slot for the leading icon — e.g. `{ Icon(Icons.Filled.DateRange, null, tint = ...) }`.
 *                 The caller controls the tint based on [hasValue].
 * @param label   Text displayed next to the icon.
 * @param hasValue When true, the label uses [MaterialTheme.colorScheme.primary].
 *                 When false, it uses [MaterialTheme.colorScheme.onSurfaceVariant].
 * @param onClick Called when the row is tapped.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun IconPickerRow(
    icon: @Composable () -> Unit,
    label: String,
    hasValue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (hasValue) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun IconPickerRowLightPreview() = PreviewThemed(darkTheme = false) {
    Column {
        IconPickerRow(
            icon = { Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            label = "Due date",
            hasValue = true,
            onClick = {},
        )
        IconPickerRow(
            icon = { Icon(Icons.Filled.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            label = "Due date",
            hasValue = false,
            onClick = {},
        )
    }
}
