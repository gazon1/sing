// The composable reads the current time only to render relative "edited 5m ago"
// labels; it performs no time-dependent state transition, so there is nothing to
// inject or test. NoDirectClockSystem exemption, same shape as the other screens.
@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.projects.presentation.components.ActiveSheet
import com.singularity.todo.feature.projects.presentation.components.CurrentProjectContent
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailSheetsHost
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavigator
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalTime::class)
@Composable
fun ProjectDetailContent(viewModel: ProjectDetailViewModel, modifier: Modifier = Modifier) {
    val nav = LocalProjectsNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastEditedAt by viewModel.lastEditedAt.collectAsStateWithLifecycle()
    val clock: Clock = Clock.System
    val sheets = rememberDialogState<ActiveSheet>()
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val draftState by viewModel.draftState.state.collectAsStateWithLifecycle()

    val actions = remember {
        ProjectDetailActions { intent ->
            when (intent) {
                is ProjectDetailIntent.Routing.OpenColorSheet -> sheets.show(ActiveSheet.PickColor)

                is ProjectDetailIntent.Routing.OpenIconSheet -> sheets.show(ActiveSheet.PickIcon)

                is ProjectDetailIntent.Routing.OpenParentSheet -> sheets.show(
                    ActiveSheet.PickParent(intent.currentParentId),
                )

                is ProjectDetailIntent.Routing.OpenDueDateSheet -> sheets.show(ActiveSheet.PickDueDate)

                is ProjectDetailIntent.Routing.OpenChildrenSheet -> sheets.show(ActiveSheet.ShowChildren)

                is ProjectDetailIntent.Routing.OpenDeleteSheet -> sheets.show(ActiveSheet.ConfirmDelete)

                is ProjectDetailIntent.Routing.OpenArchiveSheet -> sheets.show(ActiveSheet.ConfirmArchive)

                is ProjectDetailIntent.Routing.OpenReminderSheet -> sheets.show(ActiveSheet.PickReminder)

                is ProjectDetailIntent.Routing.OpenAttachmentSheet -> sheets.show(ActiveSheet.AddAttachment)

                is ProjectDetailIntent.Routing.NavigateToChild -> nav.openDetail(intent.projectId)

                is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ProjectDetailUiEvent.NavigateBack -> nav.back()
                is ProjectDetailUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val contentState = state
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.title()) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                        ) {
                            val isArchived =
                                (contentState as? ProjectDetailUiState.Content)?.ui?.project?.isDeleted == true
                            DropdownMenuItem(
                                text = { Text(if (isArchived) "Unarchive" else "Archive") },
                                onClick = {
                                    overflowMenuOpen = false
                                    actions.onOpenArchiveSheet()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    overflowMenuOpen = false
                                    actions.onOpenDeleteSheet()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (contentState is ProjectDetailUiState.Content) {
                ProjectBottomActionBar(isArchived = contentState.ui.project.isDeleted, actions = actions)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            ProjectDetailUiState.Loading -> LoadingIndicator(Modifier.padding(padding))

            ProjectDetailUiState.NotFound -> EmptyState(
                title = "Project not found",
                modifier = Modifier.padding(padding),
            )

            is ProjectDetailUiState.Content -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            ) {
                ProjectHeroSection(
                    ui = s.ui,
                    lastEditedAt = lastEditedAt,
                    now = clock.now(),
                    nameDraft = draftState.name,
                    descriptionDraft = draftState.description,
                    actions = actions,
                )
                ProjectMetaChipsRow(ui = s.ui, actions = actions)
                ProjectBodySection(
                    ui = s.ui,
                    hideCompleted = s.hideCompleted,
                    availableTasks = s.availableTasks,
                    actions = actions,
                    nav = nav,
                )
            }
        }
    }

    // ─── Sheets ────────────────────────────────────────────────────────────
    if (sheets.active != null) {
        BottomSheetHost(onDismiss = { sheets.dismiss() }) {
            val content = (contentState as? ProjectDetailUiState.Content)
            ProjectDetailSheetsHost(
                activeSheet = sheets.active,
                currentContent = content?.let { c ->
                    CurrentProjectContent(
                        name = c.ui.project.name,
                        color = c.ui.project.color,
                        icon = c.ui.project.icon,
                        parentId = c.ui.parent?.id,
                        dueDate = c.ui.project.dueDate,
                        isArchived = c.ui.project.isDeleted,
                        childProjects = c.ui.childProjects,
                        // The stored value is a raw minute count; ReminderOffset is the
                        // fixed set the picker offers, so a stored offset that no longer
                        // matches a member (offset list changed) reads as "no selection"
                        // rather than inventing a value the picker cannot show.
                        reminderOffset = content?.ui?.reminderOffsetMinutes
                            ?.let { m -> ReminderOffset.entries.firstOrNull { it.minutes == m } },
                        onUpdateColor = { actions.onUpdateColor(it) },
                        onUpdateIcon = { actions.onUpdateIcon(it) },
                        onUpdateParent = { actions.onUpdateParent(it) },
                        onUpdateDueDate = { actions.onUpdateDueDate(it) },
                        onUpdateName = { actions.onUpdateName(it) },
                        onUpdateDescription = { actions.onUpdateDescription(it) },
                        onDelete = { actions.onDelete() },
                        onToggleArchive = { actions.onToggleArchive() },
                        onSetReminder = { offset -> actions.onSetReminder(offset.minutes) },
                        onNavigateToChild = { id -> actions.onNavigateToChild(id) },
                    )
                },
                parentOptions = content?.parentOptions.orEmpty(),
                onSheetDismiss = { sheets.dismiss() },
            )
        }
    }
}

// ─── Sections ────────────────────────────────────────────────────────────────

@Composable
private fun ProjectDetailUiState.title(): String = when (this) {
    ProjectDetailUiState.Loading -> "Project"
    ProjectDetailUiState.NotFound -> "Not found"
    is ProjectDetailUiState.Content -> ui.project.name
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ProjectHeroSection(
    ui: ProjectDetailUi,
    lastEditedAt: Instant?,
    now: Instant,
    nameDraft: String,
    descriptionDraft: String,
    actions: ProjectDetailActions,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(
                    56.dp,
                ).clip(CircleShape).background(Color(ui.project.color))
                    .clickable(
                        onClickLabel = "Change project icon and color",
                        onClick = actions::onOpenColorSheet,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val icon = ProjectIconRegistry.iconByKey(ui.project.icon) ?: Icons.Filled.Folder
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { actions.onUpdateName(it) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lastEditedAt != null) {
                    Text(
                        text = formatSavedRelative(now, lastEditedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (ui.totalCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { ui.progressFraction },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(ui.project.color),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${ui.completedCount}/${ui.totalCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        BasicTextField(
            value = descriptionDraft,
            onValueChange = { actions.onUpdateDescription(it.ifBlank { null }) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (descriptionDraft.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectMetaChipsRow(ui: ProjectDetailUi, actions: ProjectDetailActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.project.dueDate?.let { date ->
            FilterChip(
                selected = false,
                onClick = actions::onOpenDueDateSheet,
                label = { Text(date.toString()) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        ui.parent?.let { parent ->
            FilterChip(
                selected = false,
                onClick = { actions.onOpenParentSheet(parent.id) },
                label = { Text(parent.name) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (ui.childProjects.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = actions::onOpenChildrenSheet,
                label = { Text("${ui.childProjects.size} sub-projects") },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun ProjectBodySection(
    ui: ProjectDetailUi,
    hideCompleted: Boolean,
    availableTasks: List<Task>,
    actions: ProjectDetailActions,
    nav: ProjectsNavigator,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ProjectDetailQuickAddInput(
            availableTasks = availableTasks,
            actions = actions,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium)
            if (ui.totalCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hideCompleted) "Show completed" else "Hide completed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = actions::onToggleHideCompleted),
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
                        // onAiClick intentionally omitted: every TaskAiAction
                        // mutates immediately (refine overwrites the title,
                        // decompose creates real subtasks) with no preview or
                        // undo. A list row is the wrong place to trigger that —
                        // the user opened the row's detail to edit it. See ADR
                        // 2026-09-30-card-level-ai-actions-deferred.
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
            TextButton(onClick = { nav.openTasks(ui.project.id) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("See all ${ui.totalCount} tasks")
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ProjectBottomActionBar(isArchived: Boolean, actions: ProjectDetailActions) {
    BottomAppBar(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = actions::onOpenReminderSheet) { Icon(Icons.Filled.Notifications, "Remind") }
        IconButton(onClick = actions::onOpenAttachmentSheet) { Icon(Icons.Filled.Folder, "Attach") }
        Spacer(Modifier.weight(1f))
        if (isArchived) {
            IconButton(
                onClick = { actions.onToggleArchive() },
            ) { Icon(Icons.Filled.PushPin, "Unarchive") }
        }
        IconButton(onClick = actions::onOpenIconSheet) { Icon(Icons.Filled.MoreVert, "More") }
    }
}

// ─── Quick Add ─────────────────────────────────────────────────────────────

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

private sealed class QuickAddSheet {
    data object Picker : QuickAddSheet()
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
        if (query.isBlank()) tasks.take(10) else tasks.filter { it.title.contains(query, ignoreCase = true) }.take(10)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
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

@OptIn(ExperimentalTime::class)
fun formatSavedRelative(now: Instant, lastEdited: Instant): String {
    val diffMs = now.toEpochMilliseconds() - lastEdited.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Saved just now"
        diffMs < 3_600_000 -> "Saved ${diffMs / 60_000}m ago"
        else -> "Saved ${diffMs / 3_600_000}h ago"
    }
}
