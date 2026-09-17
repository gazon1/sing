package com.singularity.todo.feature.agenda.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * A reorderable list of [Section] items.
 *
 * Uses `sh.calvin.reorderable` for drag-and-drop reordering. The actual
 * library is added in Commit 5; this stub uses a plain [LazyColumn] for now.
 *
 * @param sections The sections to display.
 * @param onSectionsReordered Called when the user has reordered sections
 *        with the new list as argument. The caller is responsible for updating
 *        the VM's draft state via [SavedAgendaIntent.SectionsReordered].
 * @param modifier Compose modifier.
 */
@Composable
fun ReorderableSectionList(
    sections: List<Section>,
    onSectionsReordered: (List<Section>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stub: plain LazyColumn. Will be replaced with sh.calvin.reorderable in Commit 5.
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = sections,
            key = { _, section -> "${section.name}#${section.order}" },
        ) { index, section ->
            SectionRow(
                section = section,
                index = index,
            )
        }
    }
}

/**
 * A single section row in the reorderable list.
 * Shows section name, type badge, and a drag handle.
 */
@Composable
fun SectionRow(
    section: Section,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = section.selector.typeDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human-readable description of a [Selector] for UI display. */
val Selector.typeDescription: String
    get() = when (this) {
        is Selector.DateBucket -> when (bucket) {
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.Overdue -> "Overdue tasks"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.Today -> "Today"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.Tomorrow -> "Tomorrow"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.ThisWeek -> "This week"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.NextWeek -> "Next week"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.ThisMonth -> "This month"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.NextMonth -> "Next month"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.Yesterday -> "Yesterday"
            com.singularity.todo.feature.agenda.domain.model.RelativeBucket.NoDate -> "No date"
        }
        is Selector.DateRange -> "Date range: ${from} – ${to}"
        is Selector.Statuses -> {
            val names = statuses.map { it.statusName }.sorted()
            when {
                names.size == 1 -> names[0]
                names.size == 2 && statuses.contains(TaskStatus.Active) && statuses.contains(TaskStatus.Completed) -> "All"
                else -> names.joinToString(", ")
            }
        }
        is Selector.Priorities -> {
            val prefix = if (atMost) "Priority: " else "Priority not: "
            val names = priorities.map { it.priorityName }.sorted()
            prefix + names.joinToString(", ")
        }
        is Selector.Tag -> "Tagged"
        is Selector.Tags -> when {
            matchAll -> "All tags: ${ids.size}"
            else -> "Any tag: ${ids.size}"
        }
        is Selector.Projects -> when (ids.size) {
            0 -> "No project"
            1 -> "Project"
            else -> "${ids.size} projects"
        }
        is Selector.Pinned -> "Pinned"
        is Selector.Completed -> "Completed"
        is Selector.Overdue -> "Overdue"
        is Selector.Regexp -> "Regex: $query"
        is Selector.AllOf -> "All of (${children.size} rules)"
        is Selector.AnyOf -> "Any of (${children.size} rules)"
        is Selector.Not -> "Not: ${child.typeDescription}"
        is Selector.Anything -> "All tasks"
    }

/** Name for [TaskStatus] display. */
private val TaskStatus.statusName: String
    get() = when (this) {
        TaskStatus.Active -> "Active"
        TaskStatus.Completed -> "Completed"
        TaskStatus.All -> "All"
    }

/** Name for [TaskPriority] display. */
private val TaskPriority.priorityName: String
    get() = when (this) {
        TaskPriority.High -> "High"
        TaskPriority.Medium -> "Medium"
        TaskPriority.Low -> "Low"
        TaskPriority.None -> "None"
        TaskPriority.Urgent -> "Urgent"
    }
