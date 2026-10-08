@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.singularity.todo.feature.search.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.search.query.SimpleFilter
import com.singularity.todo.feature.search.query.SortOrder
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * A bottom sheet for building a [SimpleFilter] interactively.
 *
 * Integrates into [SearchScreen] via a filter icon button in the TopAppBar.
 *
 * @param initialFilter The filter to seed the sheet's local state.
 * @param onApply Called when the user taps "Apply" with the resulting filter.
 *                Pass `null` to indicate the user wants no filter (clears active filter).
 * @param onDismiss Called when the user swipes down or taps outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleFilterSheet(
    initialFilter: SimpleFilter?,
    onApply: (SimpleFilter?) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
) {
    // Local mutable copy — not applied until user taps "Apply"
    var localStates by remember(initialFilter) {
        mutableStateOf(initialFilter?.states)
    }
    var localPriorities by remember(initialFilter) {
        mutableStateOf(initialFilter?.priorities ?: emptySet())
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filter",
                    style = MaterialTheme.typography.titleLarge,
                )
                Row {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val hasNonDefault = localStates != null ||
                                localPriorities.isNotEmpty() ||
                                localDue != SimpleFilter.DueCondition.NONE ||
                                localHasDescription != null ||
                                localPinned != null ||
                                localSortOrder != SortOrder.DUE ||
                                localSortDescending
                            onApply(
                                if (hasNonDefault) {
                                    SimpleFilter(
                                        states = localStates,
                                        priorities = localPriorities,
                                        due = localDue,
                                        hasDescription = localHasDescription,
                                        pinned = localPinned,
                                        sortOrder = localSortOrder,
                                        sortDescending = localSortDescending,
                                    )
                                } else {
                                    null
                                },
                            )
                        },
                    ) {
                        Text("Apply")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ── State ──────────────────────────────────────────────────────
            SettingsSection(title = "Status") {
                StateChips(
                    selected = localStates,
                    onSelectionChange = { localStates = it },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Priority ───────────────────────────────────────────────────
            SettingsSection(title = "Priority") {
                PriorityChips(
                    selected = localPriorities,
                    onSelectionChange = { localPriorities = it },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Due Date ───────────────────────────────────────────────────
            SettingsSection(title = "Due") {
                DueChips(
                    selected = localDue,
                    onSelectionChange = { localDue = it },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

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
private fun StateChips(selected: Set<TaskStatus>?, onSelectionChange: (Set<TaskStatus>?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelectionChange(null) },
            label = { Text("All") },
        )
        FilterChip(
            selected = selected == setOf(TaskStatus.Active),
            onClick = {
                onSelectionChange(
                    when (selected) {
                        null -> setOf(TaskStatus.Active)
                        setOf(TaskStatus.Active) -> null
                        else -> setOf(TaskStatus.Active)
                    },
                )
            },
            label = { Text("Active") },
        )
        FilterChip(
            selected = selected == setOf(TaskStatus.Completed),
            onClick = {
                onSelectionChange(
                    when (selected) {
                        null -> setOf(TaskStatus.Completed)
                        setOf(TaskStatus.Completed) -> null
                        else -> setOf(TaskStatus.Completed)
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
private fun PriorityChips(selected: Set<TaskPriority>, onSelectionChange: (Set<TaskPriority>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TaskPriority.entries.forEach { priority ->
            FilterChip(
                selected = priority in selected,
                onClick = {
                    onSelectionChange(
                        if (priority in selected) {
                            selected - priority
                        } else {
                            selected + priority
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
private fun DueChips(selected: SimpleFilter.DueCondition, onSelectionChange: (SimpleFilter.DueCondition) -> Unit) {
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
private fun SimpleFilterSheetPreview() = PreviewThemed(darkTheme = false) {
    SimpleFilterSheet(
        initialFilter = SimpleFilter(
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
private fun SimpleFilterSheetEmptyPreview() = PreviewThemed(darkTheme = false) {
    SimpleFilterSheet(
        initialFilter = null,
        onApply = {},
        onDismiss = {},
    )
}
