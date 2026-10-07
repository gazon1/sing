package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavigator
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Task list section of [ProjectDetailScreen]: quick-add input, task cards, and
 * "See all N tasks" link.
 *
 * Exposed as public to support preview providers and unit tests without a VM.
 */
@Composable
fun ProjectBodySection(
    ui: ProjectDetailUi,
    hideCompleted: Boolean,
    hideBlocked: Boolean,
    availableTasks: List<Task>,
    actions: ProjectDetailActions,
    nav: ProjectsNavigator,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ProjectDetailQuickAddInput(
            availableTasks = availableTasks,
            actions = actions,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium)
            if (ui.totalCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Same text-link idiom as the completed toggle, so the two
                    // read as one control rather than two competing styles.
                    Text(
                        if (hideCompleted) "Show completed" else "Hide completed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = actions::onToggleHideCompleted),
                    )
                    Text(
                        "  ·  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (hideBlocked) "Show blocked" else "Hide blocked",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = actions::onToggleHideBlocked),
                    )
                }
            }
        }
        if (ui.tasks.isEmpty()) {
            EmptyState(title = "No tasks yet", modifier = Modifier.padding(32.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(ui.tasks, key = { it.id.value }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { nav.openTask(task.id) },
                        actions = TaskCardActions(
                            onPin = { actions.onPin(task.id) },
                            onDelete = { actions.onDeleteTask(task.id) },
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (ui.totalCount > 5) {
            TextButton(
                onClick = { nav.openTasks(ui.project.id) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("See all ${ui.totalCount} tasks")
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

/**
 * Whether project reminders can actually fire.
 *
 * False on **every** platform, unlike [com.singularity.todo.feature.reminders.ReminderScheduler.isSupported]
 * which is false only on desktop. A `ProjectReminder` row has no consumer anywhere:
 * there is no scheduler, and no receiver branch. Flipping this to `true` is the whole
 * of the remaining work for the slice, and it must not be flipped before the scheduler
 * exists — the failure mode is a reminder that reads as armed and never arrives.
 *
 * Read by [ProjectBottomActionBar] to gate the bell.
 */
const val PROJECT_REMINDERS_SUPPORTED: Boolean = false

/**
 * Bottom action bar with reminder, attachment, archive/unarchive, and more buttons.
 *
 * ## The reminder button is disabled, and it is not a platform limitation
 *
 * `ProjectReminder` is persisted but **no platform ever fires it**: there is no
 * `ProjectReminderScheduler` on any platform, and `AlarmReceiver` has no project
 * branch. `docs/decisions/2026-09-30-project-reminder-own-table.md` states this
 * outright — the model and UI shipped, the fire path is the deliberate follow-up.
 *
 * So the bell would have persisted a reminder that can never arrive, on Android as
 * well as desktop. It is kept, disabled, rather than deleted: the intent, the sheet,
 * the repository and the `fireAt` maths are all correct and tested, and the follow-up
 * only has to supply the scheduler and flip [PROJECT_REMINDERS_SUPPORTED].
 *
 * Deleting the button instead would leave the whole vertical slice unwired, which is
 * the defect `scripts/find-unwired-surfaces.py` exists to catch — trading a visible
 * lie for an invisible half-feature.
 */
@Composable
fun ProjectBottomActionBar(isArchived: Boolean, actions: ProjectDetailActions, modifier: Modifier = Modifier) {
    BottomAppBar(modifier = modifier.fillMaxWidth()) {
        IconButton(
            onClick = actions::onOpenReminderSheet,
            enabled = PROJECT_REMINDERS_SUPPORTED,
        ) {
            Icon(Icons.Filled.Notifications, "Remind (not yet available)")
        }
        IconButton(onClick = actions::onOpenAttachmentSheet) {
            Icon(Icons.Filled.Folder, "Attach")
        }
        Spacer(Modifier.weight(1f))
        if (isArchived) {
            IconButton(onClick = { actions.onToggleArchive() }) {
                Icon(Icons.Filled.PushPin, "Unarchive")
            }
        }
        IconButton(onClick = actions::onOpenIconSheet) {
            Icon(Icons.Filled.MoreVert, "More")
        }
    }
}

// ─── Quick Add (private — only used by ProjectBodySection) ─────────────────────

sealed class QuickAddSheet {
    data object Picker : QuickAddSheet()
}

@Composable
private fun ProjectDetailQuickAddInput(
    availableTasks: List<Task>,
    actions: ProjectDetailActions,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val popup = rememberOverlayState<QuickAddSheet>()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    Box(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { popup.show(QuickAddSheet.Picker) }) {
                Icon(Icons.Filled.Add, "Add existing task", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Add a task...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PROJECT_DETAIL_QUICK_ADD),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (text.isNotBlank()) {
                            actions.onCreateTask(text)
                            text = ""
                            focus.clearFocus()
                        }
                    },
                ),
            )
        }
        if (popup.sheet == QuickAddSheet.Picker) {
            AddExistingTaskPopup(
                tasks = availableTasks,
                query = query,
                onQueryChange = { query = it },
                onPick = { taskId ->
                    actions.onMoveTaskToProject(taskId)
                    popup.dismissAll()
                    query = ""
                },
                onDismiss = {
                    popup.dismissAll()
                    query = ""
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExistingTaskPopup(
    tasks: List<Task>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (TaskId) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = remember(tasks, query) {
        if (query.isBlank()) {
            tasks.take(10)
        } else {
            tasks.filter { it.title.contains(query, ignoreCase = true) }.take(10)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Add existing task", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search tasks...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    "No tasks found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.id.value }) { task ->
                        ListItem(
                            headlineContent = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { onPick(task.id) },
                        )
                    }
                }
            }
        }
    }
}

// ─── Pure formatter ─────────────────────────────────────────────────────────

/**
 * Formats a saved-at timestamp as a human-readable relative string.
 */
@OptIn(ExperimentalTime::class)
fun formatSavedRelative(now: Instant, lastEdited: Instant): String {
    val diffMs = now.toEpochMilliseconds() - lastEdited.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Saved just now"
        diffMs < 3_600_000 -> "Saved ${diffMs / 60_000}m ago"
        else -> "Saved ${diffMs / 3_600_000}h ago"
    }
}
