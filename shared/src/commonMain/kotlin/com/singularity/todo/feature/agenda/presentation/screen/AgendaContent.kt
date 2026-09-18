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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.core.ui.menu.onSecondaryClick
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.domain.model.AgendaBadge
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaRowItem
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.presentation.components.list.SwipeableTaskRow
import com.singularity.todo.feature.tasks.presentation.components.list.TaskRowFlat
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Content composable for the Agenda screen.
 *
 * Stateless — receives [AgendaUiState] and emits [AgendaIntent] via [onIntent].
 * Used by [AgendaScreen] (production) and preview.
 *
 * The [desktopContextMenuHost] slot is the desktop (JVM) context menu renderer.
 * On Android it is a no-op (default). On Desktop it is provided by the
 * platform-specific [AgendaNavGraph][com.singularity.todo.feature.agenda.presentation.nav.AgendaNavGraph]
 * implementation and includes the actual [com.singularity.todo.core.ui.menu.ContextMenuHost].
 *
 * @param state The current agenda UI state.
 * @param title Title to display in the top app bar.
 * @param onIntent Called when the user performs an action.
 * @param onSavedViewsClick Called when the user taps the saved views action.
 * @param onSaveCurrentClick Called when the user taps the save-current-agenda action.
 * @param desktopContextMenuHost Slot for the desktop context menu renderer.
 * @param modifier Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaContent(
    state: AgendaUiState,
    title: String,
    onIntent: (AgendaIntent) -> Unit,
    onSavedViewsClick: (() -> Unit)? = null,
    onSaveCurrentClick: (() -> Unit)? = null,
    desktopContextMenuHost: @Composable (taskUi: TaskUi, offset: DpOffset, onDismiss: () -> Unit, onIntent: (AgendaIntent) -> Unit) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Routing state: which task is right-clicked and where.
    // Managed here in the screen layer per ui-event-vs-state skill.
    var contextMenuTask by remember { mutableStateOf<TaskUi?>(null) }
    var contextMenuOffset by remember { mutableStateOf<DpOffset?>(null) }

    fun openContextMenu(taskUi: TaskUi, offset: DpOffset) {
        contextMenuTask = taskUi
        contextMenuOffset = offset
    }

    fun dismissContextMenu() {
        contextMenuTask = null
        contextMenuOffset = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (onSavedViewsClick != null) {
                        IconButton(onClick = onSavedViewsClick) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Saved views")
                        }
                    }
                    if (onSaveCurrentClick != null) {
                        IconButton(onClick = onSaveCurrentClick) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Save current agenda")
                        }
                    }
                },
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
                        onOpenContextMenu = ::openContextMenu,
                        modifier = Modifier.padding(paddingValues),
                    )
                }
            }
        }
    }

    // Render the desktop context menu (no-op on Android).
    val task = contextMenuTask
    val offset = contextMenuOffset
    if (task != null && offset != null) {
        desktopContextMenuHost(task, offset, ::dismissContextMenu, onIntent)
    }
}

@Composable
private fun AgendaList(
    sections: List<RenderedSection>,
    today: LocalDate,
    onIntent: (AgendaIntent) -> Unit,
    onOpenContextMenu: (TaskUi, DpOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (section in sections) {
            item(key = "header-${section.name}") {
                AgendaSectionHeader(name = section.name, badge = section.badge)
            }

            items(
                items = section.tasks,
                key = { it.task.id.value },
            ) { rowItem ->
                AgendaTaskRow(
                    rowItem = rowItem,
                    today = today,
                    onIntent = onIntent,
                    onOpenContextMenu = onOpenContextMenu,
                )
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
private fun AgendaTaskRow(
    rowItem: AgendaRowItem,
    today: LocalDate,
    onIntent: (AgendaIntent) -> Unit,
    onOpenContextMenu: (TaskUi, DpOffset) -> Unit,
) {
    val task = rowItem.task
    val isOverdue = TaskComputed.isOverdue(task, today)
    val taskUi = TaskUi(
        id = task.id,
        title = task.title,
        project = null,
        dueLabel = formatRussianDueDate(task.dueDate),
        parentId = task.parentTaskId?.value,
        indentLevel = if (task.parentTaskId != null) 1 else 0,
        isRecurring = false,
        isPinned = task.isPinned,
        priority = task.priority,
        isCompleted = task.completedAt != null,
        isOverdue = isOverdue,
        isSelected = false,
        dependsOn = task.dependsOn,
        isBlocked = rowItem.isBlocked,
    )

    // Right-click handler — uses onSecondaryClick (expect/actual, jvmMain actual).
    // Passed via secondaryClickModifier so SwipeToDismissBox doesn't intercept the event.
    val rightClickModifier = Modifier.onSecondaryClick { offset ->
        onOpenContextMenu(taskUi, offset)
    }

    SwipeableTaskRow(
        onDelete = { onIntent(AgendaIntent.TaskCheckClicked(task.id)) },
        secondaryClickModifier = rightClickModifier,
        content = {
            TaskRowFlat(
                task = taskUi,
                onToggleCompleted = { onIntent(AgendaIntent.TaskCheckClicked(task.id)) },
                onClick = { onIntent(AgendaIntent.TaskClicked(task.id)) },
                showDivider = true,
            )
        },
    )
}

@Preview
@Composable
private fun AgendaContentLoadingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AgendaContent(
        state = AgendaUiState.Loading,
        title = "Agenda",
        onIntent = {},
    )
}

@Preview
@Composable
private fun AgendaContentErrorPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AgendaContent(
        state = AgendaUiState.Error(message = "Failed to load agenda"),
        title = "Agenda",
        onIntent = {},
    )
}

@Preview
@Composable
private fun AgendaContentEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    AgendaContent(
        state = AgendaUiState.Loaded(
            sections = emptyList(),
            today = PreviewSamples.today,
        ),
        title = "Agenda",
        onIntent = {},
    )
}

@Preview
@Composable
private fun AgendaContentLoadedPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val today = PreviewSamples.today
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    val tomorrow = today.plus(1, DateTimeUnit.DAY)

    val overdueTask = PreviewSamples.task(
        id = "t1",
        title = "Overdue task",
        dueDate = yesterday,
        completed = false,
    )
    val todayTask = PreviewSamples.task(
        id = "t2",
        title = "Due today",
        dueDate = today,
        completed = false,
    )
    val completedTask = PreviewSamples.task(
        id = "t3",
        title = "Completed task",
        dueDate = today,
        completed = true,
    )
    val pinnedTask = PreviewSamples.task(
        id = "t4",
        title = "Pinned task",
        dueDate = tomorrow,
        pinned = true,
    )
    val noDateTask = PreviewSamples.task(
        id = "t5",
        title = "No date set",
        dueDate = null,
        completed = false,
    )

    AgendaContent(
        state = AgendaUiState.Loaded(
            sections = listOf(
                RenderedSection(
                    name = "Overdue",
                    tasks = listOf(AgendaRowItem(task = overdueTask, badge = AgendaBadge.Overdue, isBlocked = false)),
                    badge = 1,
                ),
                RenderedSection(
                    name = "Today",
                    tasks = listOf(AgendaRowItem(task = todayTask, isBlocked = false)),
                    badge = null,
                ),
                RenderedSection(
                    name = "Completed",
                    tasks = listOf(AgendaRowItem(task = completedTask, badge = AgendaBadge.Completed, isBlocked = false)),
                    badge = null,
                ),
                RenderedSection(
                    name = "Upcoming",
                    tasks = listOf(
                        AgendaRowItem(task = pinnedTask, badge = AgendaBadge.Pinned, isBlocked = false),
                        AgendaRowItem(task = noDateTask, badge = AgendaBadge.NoDate, isBlocked = false),
                    ),
                    badge = null,
                ),
            ),
            today = today,
        ),
        title = "My Agenda",
        onIntent = {},
        onSavedViewsClick = {},
        onSaveCurrentClick = {},
    )
}

@Preview
@Composable
private fun AgendaContentDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    val today = PreviewSamples.today

    AgendaContent(
        state = AgendaUiState.Loaded(
            sections = listOf(
                RenderedSection(
                    name = "Today",
                    tasks = listOf(
                        AgendaRowItem(task = PreviewSamples.task(id = "t1", title = "Review PR", dueDate = today)),
                        AgendaRowItem(task = PreviewSamples.task(id = "t2", title = "Send report", dueDate = today)),
                    ),
                    badge = null,
                ),
            ),
            today = today,
        ),
        title = "Agenda",
        onIntent = {},
        onSavedViewsClick = {},
        onSaveCurrentClick = {},
    )
}
