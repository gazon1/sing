package com.singularity.todo.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A single item in a [ListPickerSheet].
 *
 * @param key          The value emitted when this item is selected.
 * @param label        Primary text shown in the row.
 * @param subtitle     Optional secondary text.
 * @param selected     Whether this item is currently selected.
 * @param enabled      Whether the item is interactive. Defaults to true.
 * @param leading      Leading composable slot — called with [RowScope].
 */
data class ListPickerItem<T>(
    val key: T,
    val label: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val leading: @Composable (RowScope.() -> Unit) = {},
) where T : Any?

/**
 * A bottom sheet that displays a flat list of selectable items.
 *
 * ```
 * ListPickerSheet(
 *     title = "Add section",
 *     items = listOf(
 *         ListPickerItem("Active tasks", Selector.Statuses(setOf(TaskStatus.Active))),
 *     ),
 *     onItemSelected = { selector -> ... },
 *     onDismiss = { ... },
 * )
 * ```
 *
 * @param title        Sheet title.
 * @param items        List of items to display.
 * @param onItemSelected Called with the item's [ListPickerItem.key] when the user taps an item.
 * @param onDismiss    Called when the sheet is dismissed.
 * @param sheetState   Sheet state. Created lazily by default.
 * @param leading      Optional leading slot applied to every row — called with [RowScope].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any?> ListPickerSheet(
    title: String,
    items: List<ListPickerItem<T>>,
    onItemSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    leading: @Composable (RowScope.() -> Unit) = {},
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            header?.invoke(this)

            if (items.isEmpty()) {
                Text(
                    text = "No items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(items, key = { it.key.toString() }) { item ->
                        ListPickerItemRow(
                            item = item,
                            onSelect = { onItemSelected(item.key) },
                            leading = leading,
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
private fun <T> ListPickerItemRow(
    item: ListPickerItem<T>,
    onSelect: () -> Unit,
    leading: @Composable (RowScope.() -> Unit),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.enabled) Modifier.clickable(onClick = onSelect)
                else Modifier,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
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
                if (item.subtitle != null) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
