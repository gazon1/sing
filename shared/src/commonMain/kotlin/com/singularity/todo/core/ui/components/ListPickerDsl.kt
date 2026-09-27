package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

/**
 * DSL marker so that the trailing lambda can only be called from inside [ListPickerSheet].
 */
@DslMarker
annotation class ListPickerDsl

/**
 * Receiver scope for the [ListPickerSheet] trailing lambda.
 *
 * Add items imperatively inside the lambda:
 * ```
 * ListPickerSheet<Selector>(
 *     title = "Add section",
 *     onItemSelected = { ... },
 *     onDismiss = { ... },
 * ) {
 *     item("Active tasks", Selector.Statuses(setOf(TaskStatus.Active)))
 *     item("Completed tasks", Selector.Statuses(setOf(TaskStatus.Completed)))
 * }
 * ```
 *
 * @param T The type of the key emitted when an item is selected.
 */
@ListPickerDsl
class ListPickerScope<T> internal constructor() {

    internal val items: MutableList<ListPickerItem<T>> = mutableListOf()

    internal var headerSlot: (@Composable ColumnScope.() -> Unit)? = null
    internal var footerSlot: (@Composable ColumnScope.() -> Unit)? = null

    /**
     * Adds a header composable rendered above the item list.
     */
    fun header(content: @Composable ColumnScope.() -> Unit) {
        headerSlot = content
    }

    /**
     * Adds a footer composable rendered below the item list.
     */
    fun footer(content: @Composable ColumnScope.() -> Unit) {
        footerSlot = content
    }

    /**
     * Adds a selectable item to the list.
     *
     * @param label    Primary text shown in the row.
     * @param key      Value emitted via [onItemSelected] when this item is tapped.
     * @param subtitle Optional secondary text shown below [label].
     * @param leading  Optional leading composable called with [RowScope] — rendered before the label column.
     * @param selected Whether this item appears as selected. No automatic highlighting is applied;
     *                 callers can use this to pre-select an item.
     * @param enabled  Whether this item is interactive. Defaults to true.
     */
    fun item(
        label: String,
        key: T,
        subtitle: String? = null,
        leading: @Composable (RowScope.() -> Unit) = {},
        selected: Boolean = false,
        enabled: Boolean = true,
    ) {
        items.add(
            ListPickerItem(
                key = key,
                label = label,
                subtitle = subtitle,
                selected = selected,
                enabled = enabled,
                leading = leading,
            ),
        )
    }

    /**
     * Convenience overload with no subtitle or leading slot.
     */
    fun item(label: String, key: T) {
        item(label = label, key = key, subtitle = null, leading = {})
    }
}

/**
 * Overload of [ListPickerSheet] that accepts a trailing lambda with a [ListPickerScope] receiver
 * instead of a pre-built [ListPickerItem] list.
 *
 * ```
 * ListPickerSheet<Selector>(
 *     title = "Add section",
 *     onItemSelected = { selector -> ... },
 *     onDismiss = { ... },
 * ) {
 *     item("Active tasks", Selector.Statuses(setOf(TaskStatus.Active)))
 *     item("Due today", Selector.DateBucket(RelativeBucket.Today))
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ListPickerSheet(
    title: String,
    onItemSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    block: ListPickerScope<T>.() -> Unit,
) {
    val scope = ListPickerScope<T>().apply(block)
    ListPickerSheet(
        title = title,
        items = scope.items,
        onItemSelected = onItemSelected,
        onDismiss = onDismiss,
        sheetState = sheetState,
        header = scope.headerSlot,
        footer = scope.footerSlot,
    )
}
