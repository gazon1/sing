package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskFilter

/**
 * Generic row of filter chips. Selecting one fires [onSelect].
 *
 * Replaces a hardcoded 4-chip Row that lived inline in TasksScreen. The
 * concrete [TaskFilter] entries are passed in so the row remains usable
 * outside the task screen if filters are added or removed.
 */
@Composable
fun FilterChipsRow(selected: TaskFilter, onSelect: (TaskFilter) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(
        TaskFilter.Today,
        TaskFilter.Upcoming,
        TaskFilter.Someday,
        TaskFilter.Inbox,
        TaskFilter.Pinned,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { filter ->
            FilterChip(
                selected = filter::class == selected::class,
                onClick = { onSelect(filter) },
                label = { Text(filter.label()) },
            )
        }
    }
}

private fun TaskFilter.label(): String = when (this) {
    is TaskFilter.Today -> "Today"
    is TaskFilter.Upcoming -> "Upcoming"
    is TaskFilter.Someday -> "Someday"
    is TaskFilter.Inbox -> "Inbox"
    is TaskFilter.Pinned -> "Pinned"
    is TaskFilter.Trash -> "Trash"
    is TaskFilter.All -> "All"
    is TaskFilter.ByProject -> "Project"
    is TaskFilter.ByTag -> "Tag"
    is TaskFilter.Search -> "Search"
    is TaskFilter.ByDateRange -> "Date Range"
    is TaskFilter.ByStatuses -> "By Status"
    is TaskFilter.ByTags -> "Tags"
    is TaskFilter.ByPriorities -> "Priorities"
    is TaskFilter.ByRegexp -> "Regexp"
    is TaskFilter.ByDateBucket -> "Date Bucket"
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun FilterChipsRowTodaySelectedPreview() = PreviewThemed(darkTheme = false) {
    FilterChipsRow(
        selected = TaskFilter.Today,
        onSelect = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun FilterChipsRowUpcomingSelectedDarkPreview() = PreviewThemed(darkTheme = true) {
    FilterChipsRow(
        selected = TaskFilter.Upcoming,
        onSelect = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun FilterChipsRowPinnedSelectedPurpleDarkPreview() = PreviewThemed(
    darkTheme = true,
    accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
) {
    FilterChipsRow(
        selected = TaskFilter.Pinned,
        onSelect = {},
    )
}
