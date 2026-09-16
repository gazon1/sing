package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.datetime.LocalDate

/**
 * Content composable for the Agenda screen.
 *
 * Stateless — receives [AgendaUiState] and emits [AgendaIntent] via [onIntent].
 * Used by [AgendaScreen] (production) and preview.
 *
 * @param state The current agenda UI state.
 * @param title Title to display in the top app bar.
 * @param onIntent Called when the user performs an action.
 * @param modifier Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaContent(
    state: AgendaUiState,
    title: String,
    onIntent: (AgendaIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        when (state) {
            is AgendaUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is AgendaUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is AgendaUiState.Loaded -> {
                if (state.sections.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No tasks",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AgendaList(
                        sections = state.sections,
                        today = state.today,
                        onIntent = onIntent,
                        modifier = Modifier.padding(paddingValues),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaList(
    sections: List<com.singularity.todo.feature.agenda.domain.model.RenderedSection>,
    today: LocalDate,
    onIntent: (AgendaIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (section in sections) {
            // Section header
            item(key = "header-${section.name}") {
                AgendaSectionHeader(name = section.name, badge = section.badge)
            }

            // Task rows
            items(
                items = section.tasks,
                key = { it.task.id.value },
            ) { rowItem ->
                AgendaTaskRow(rowItem = rowItem, today = today, onIntent = onIntent)
            }
        }
    }
}

@Composable
private fun AgendaSectionHeader(name: String, badge: Int?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val label = if (badge != null && badge > 0) "$name  ·  $badge" else name
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AgendaTaskRow(rowItem: AgendaRowItem, today: LocalDate, onIntent: (AgendaIntent) -> Unit) {
    val task = rowItem.task
    val isOverdue = task.completedAt == null && task.dueDate != null && task.dueDate < today
    val taskUi = TaskUi(
        id = task.id,
        title = task.title,
        project = null,
        dueLabel = formatRussianDueDate(task.dueDate),
        parentId = task.parentTaskId?.value,
        indentLevel = if (task.parentTaskId != null) 1 else 0,
        isRecurring = false,
        priority = task.priority,
        isCompleted = task.completedAt != null,
        isOverdue = isOverdue,
        isSelected = false,
        domainTask = task,
    )

    TaskRowFlat(
        task = taskUi,
        onToggleCompleted = { onIntent(AgendaIntent.TaskCheckClicked(task.id)) },
        onClick = { onIntent(AgendaIntent.TaskClicked(task.id)) },
        showDivider = true,
    )
}
