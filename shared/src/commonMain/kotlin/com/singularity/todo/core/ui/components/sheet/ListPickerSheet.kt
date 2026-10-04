package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds

/**
 * A single item in a [ListPickerSheet].
 *
 * @param key      Value emitted when this item is selected.
 * @param label    Primary text.
 * @param subtitle Optional secondary text.
 * @param selected Whether this item is currently selected — renders a trailing check.
 * @param enabled  Whether the item is interactive. Defaults to true.
 * @param leading  Per-item leading slot — called with [RowScope].
 * @param testTag  Optional tag applied to the row for UI automation. Callers
 *                 build it from `TestTags` (e.g. `TestTags.profileItem(name)`)
 *                 so Maestro/UIAutomator selectors resolve to the row.
 */
data class ListPickerItem<T>(
    val key: T,
    val label: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val leading: @Composable (RowScope.() -> Unit) = {},
    val testTag: String? = null,
) where T : Any?

/**
 * Bottom sheet that displays a flat list of selectable items.
 *
 * Tapping an item calls [onItemSelected] and then dismisses the sheet
 * (through [BottomSheetHost]'s animated dismiss). Selected items render
 * a trailing check mark.
 *
 * @param title            Sheet title.
 * @param items            Items to display.
 * @param onItemSelected   Called with the item's [ListPickerItem.key] on tap.
 * @param onDismiss        Called after the sheet is hidden.
 * @param header           Optional content between the title and the list.
 * @param footer           Optional content after the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ListPickerSheet(
    title: String,
    items: List<ListPickerItem<T>>,
    onItemSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )

            header?.invoke(this)

            if (items.isEmpty()) {
                Text(
                    text = "No items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 480.dp), // защита от вытеснения footer-а
                ) {
                    items(items, key = { it.key.toString() }) { item ->
                        ListPickerItemRow(
                            item = item,
                            onSelect = {
                                onItemSelected(item.key)
                                onDismiss()
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }

            footer?.invoke(this)
        }
    }
}

@Composable
private fun <T> ListPickerItemRow(item: ListPickerItem<T>, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            // A modal sheet is its own Android window, so the app-root
            // testTagsAsResourceId does not reach the rows. Without this every
            // picker's testTag is invisible to Maestro and callers are pushed
            // into selecting by visible label instead.
            .mapTestTagsAsResourceIds()
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(
                if (item.enabled) Modifier.clickable(onClick = onSelect) else Modifier,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item.leading(this)
        Box(modifier = Modifier.weight(1f)) {
            Column {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                item.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item.selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
