package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Which side the drag handle sits on.
 */
enum class HandleSide {
    Leading,
    Trailing,
}

/**
 * A row component with a drag handle, text, optional subtitle, and a trailing slot.
 *
 * ```
 * DragHandleRow(
 *     text = "Active tasks",
 *     subtitle = "All incomplete tasks",
 *     trailing = { Checkbox(checked = true, onCheckedChange = null) },
 *     handleSide = HandleSide.Leading,
 * )
 * ```
 *
 * The drag handle is purely visual — no runtime drag state is managed by this component.
 * See [ReorderableSectionList] for the full drag-and-drop list.
 *
 * @param handleSide  Which side the handle icon appears on. Defaults to [HandleSide.Trailing].
 * @param padding     Content padding. Defaults to horizontal 16.dp, vertical 12.dp.
 * @param text        Primary text label.
 * @param subtitle    Optional secondary text shown below [text].
 * @param onClick     Optional click handler for the whole row.
 * @param handle      Composable for the drag handle icon. Defaults to [DragHandleIcon].
 * @param trailing    Trailing slot — called with [RowScope] so children can use [RowScope] APIs.
 */
@Composable
fun DragHandleRow(
    text: String,
    modifier: Modifier = Modifier,
    handleSide: HandleSide = HandleSide.Trailing,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    handle: @Composable () -> Unit = { DragHandleIcon() },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val handleComposable: @Composable () -> Unit = handle
    val trailingComposable: @Composable RowScope.() -> Unit = trailing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (handleSide) {
            HandleSide.Leading -> {
                handleComposable()
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ContentColumn(text = text, subtitle = subtitle)
                }
                Box(modifier = Modifier.weight(1f)) {}
                trailingComposable()
            }

            HandleSide.Trailing -> {
                ContentColumn(text = text, subtitle = subtitle)
                Box(modifier = Modifier.weight(1f)) {}
                trailingComposable()
                Box(modifier = Modifier.padding(start = 12.dp)) {
                    handleComposable()
                }
            }
        }
    }
}

@Composable
private fun ContentColumn(text: String, subtitle: String?) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Default drag handle icon — the standard Material "drag handle" glyph.
 */
@Composable
fun DragHandleIcon() {
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = "Drag to reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
