@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.singularity.todo.feature.search.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.components.sheet.SheetActions
import com.singularity.todo.core.ui.components.sheet.SheetScaffold
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet

/**
 * A bottom sheet for building a [SimpleFilter] interactively.
 *
 * Integrates into [SearchScreen] via a filter icon button in the TopAppBar.
 *
 * The sheet is the house scaffold — [BottomSheetHost] and [SheetScaffold] — so
 * the insets are the window's own and the action row is the last thing in the
 * scrollable column: the chips and the Apply/Cancel pair are scrolled into
 * view together, and neither of them can end up below the system navigation.
 * The sheet that had its own scroll container and its own bottom padding put
 * Apply and Cancel in a header at the top, and they left the accessibility tree
 * as soon as the body was scrolled — measured on a 360 dp phone.
 *
 * @param initialFilter The filter to seed the sheet's local state, and the
 *                filter the applied one is built from: the parts of the query
 *                the sheet does not edit — the free text, the tags, the project
 *                — are carried over, not dropped.
 * @param onApply Called when the user taps "Apply" with the resulting filter.
 * @param onDismiss Called when the user swipes down or taps outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleFilterSheet(
    initialFilter: SimpleFilter?,
    onApply: (SimpleFilter) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
) {
    // Local mutable copy — not applied until user taps "Apply".
    // The sets are persistent: the chips below take them, and a persistent set
    // is compared by content, so a chip row is not recomposed for nothing.
    var localStates by remember(initialFilter) {
        mutableStateOf(initialFilter?.states?.toImmutableSet())
    }
    var localPriorities by remember(initialFilter) {
        mutableStateOf(initialFilter?.priorities?.toImmutableSet() ?: persistentSetOf())
    }
    var localDue by remember(initialFilter) {
        mutableStateOf(initialFilter?.due ?: SimpleFilter.DueCondition.NONE)
    }
    var localHasDescription by remember(initialFilter) {
        mutableStateOf(initialFilter?.hasDescription)
    }
    var localPinned by remember(initialFilter) {
        mutableStateOf(initialFilter?.pinned)
    }
    var localSortOrder by remember(initialFilter) {
        mutableStateOf(initialFilter?.sortOrder ?: SortOrder.DUE)
    }
    var localSortDescending by remember(initialFilter) {
        mutableStateOf(initialFilter?.sortDescending ?: false)
    }

    // The applied filter is the filter of the query with the sheet's facets.
    // The free text, the tags and the project stay: they are the parts of the
    // query the sheet does not edit, and an Apply that dropped them answered a
    // different question than the one on the screen.
    val appliedFilter =
        (initialFilter ?: SimpleFilter()).copy(
            states = localStates,
            priorities = localPriorities,
            due = localDue,
            hasDescription = localHasDescription,
            pinned = localPinned,
            sortOrder = localSortOrder,
            sortDescending = localSortDescending,
        )

    BottomSheetHost(onDismiss = onDismiss, sheetState = sheetState) {
        SheetScaffold(
            actions =
                SheetActions(
                    onCancel = onDismiss,
                    onConfirm = {
                        // Nothing to apply and nothing to keep: a query that
                        // cannot be expressed as a filter stays as it is. An
                        // applied filter would replace it with an empty query,
                        // and the search would answer a question nobody asked.
                        if (initialFilter != null || !appliedFilter.isEmpty) {
                            onApply(appliedFilter)
                        }
                        onDismiss()
                    },
                    confirmLabel = "Apply",
                    cancelLabel = "Cancel",
                    testTagConfirm = TestTags.SearchFilter.APPLY_BUTTON,
                    testTagCancel = TestTags.SearchFilter.CANCEL_BUTTON,
                ),
        ) {
            Text(
                text = "Filter",
                style = MaterialTheme.typography.titleLarge,
            )

            HorizontalDivider()

            // ── State ──────────────────────────────────────────────────────
            SettingsSection(title = "Status") {
                StateChips(
                    selected = localStates,
                    onSelectionChange = { localStates = it },
                )
            }

            // ── Priority ───────────────────────────────────────────────────
            SettingsSection(title = "Priority") {
                PriorityChips(
                    selected = localPriorities,
                    onSelectionChange = { localPriorities = it },
                )
            }

            // ── Due Date ───────────────────────────────────────────────────
            SettingsSection(title = "Due") {
                DueChips(
                    selected = localDue,
                    onSelectionChange = { localDue = it },
                )
            }

            // ── Other ──────────────────────────────────────────────────────
            SettingsSection(title = "Other") {
                SettingsSwitchRow(
                    title = "Has description",
                    testTag = TestTags.SearchFilter.HAS_DESCRIPTION_SWITCH,
                    checked = localHasDescription == true,
                    onCheckedChange = { localHasDescription = if (it) true else null },
                )
                SettingsSwitchRow(
                    title = "Pinned",
                    testTag = TestTags.SearchFilter.PINNED_SWITCH,
                    checked = localPinned == true,
                    onCheckedChange = { localPinned = if (it) true else null },
                )
            }

            // ── Sort ───────────────────────────────────────────────────────
            SettingsSection(title = "Sort") {
                SortChips(
                    sortOrder = localSortOrder,
                    sortDescending = localSortDescending,
                    onSortOrderChange = { localSortOrder = it },
                    onSortDescendingChange = { localSortDescending = it },
                )
            }
        }
    }
}

// ─── State chips ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StateChips(
    selected: ImmutableSet<TaskStatus>?,
    onSelectionChange: (ImmutableSet<TaskStatus>?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelectionChange(null) },
            label = { Text("All") },
        )
        FilterChip(
            selected = selected != null && selected.size == 1 && TaskStatus.Active in selected,
            onClick = {
                onSelectionChange(
                    when {
                        selected == null -> persistentSetOf(TaskStatus.Active)
                        selected.size == 1 && TaskStatus.Active in selected -> null
                        else -> persistentSetOf(TaskStatus.Active)
                    },
                )
            },
            label = { Text("Active") },
        )
        FilterChip(
            selected = selected != null && selected.size == 1 && TaskStatus.Completed in selected,
            onClick = {
                onSelectionChange(
                    when {
                        selected == null -> persistentSetOf(TaskStatus.Completed)
                        selected.size == 1 && TaskStatus.Completed in selected -> null
                        else -> persistentSetOf(TaskStatus.Completed)
                    },
                )
            },
            label = { Text("Completed") },
        )
    }
}

// ─── Priority chips ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityChips(
    selected: ImmutableSet<TaskPriority>,
    onSelectionChange: (ImmutableSet<TaskPriority>) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TaskPriority.entries.forEach { priority ->
            FilterChip(
                selected = priority in selected,
                onClick = {
                    onSelectionChange(
                        if (priority in selected) {
                            selected.filter { it != priority }.toImmutableSet()
                        } else {
                            (selected + priority).toImmutableSet()
                        },
                    )
                },
                label = { Text(priority.name) },
            )
        }
    }
}

// ─── Due chips ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueChips(
    selected: SimpleFilter.DueCondition,
    onSelectionChange: (SimpleFilter.DueCondition) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SimpleFilter.DueCondition.entries.forEach { due ->
            if (due != SimpleFilter.DueCondition.CUSTOM) {
                FilterChip(
                    selected = selected == due,
                    onClick = { onSelectionChange(due) },
                    label = { Text(due.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
    }
}

// ─── Sort chips ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortChips(
    sortOrder: SortOrder,
    sortDescending: Boolean,
    onSortOrderChange: (SortOrder) -> Unit,
    onSortDescendingChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortOrderChange(order) },
                    label = { Text(order.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Descending",
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = sortDescending,
                onCheckedChange = onSortDescendingChange,
            )
        }
    }
}

// ─── Preview ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SimpleFilterSheetPreview() =
    PreviewThemed(darkTheme = false) {
        SimpleFilterSheet(
            initialFilter =
                SimpleFilter(
                    states = setOf(TaskStatus.Active),
                    priorities = setOf(TaskPriority.High),
                    due = SimpleFilter.DueCondition.TODAY,
                    sortOrder = SortOrder.PRIORITY,
                    sortDescending = true,
                ),
            onApply = {},
            onDismiss = {},
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SimpleFilterSheetEmptyPreview() =
    PreviewThemed(darkTheme = false) {
        SimpleFilterSheet(
            initialFilter = null,
            onApply = {},
            onDismiss = {},
        )
    }
