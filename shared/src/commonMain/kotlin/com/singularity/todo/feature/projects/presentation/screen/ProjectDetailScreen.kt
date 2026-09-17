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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.DatePickerSheet
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.model.ParentOption
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavigator
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.theme.ProjectColorPalette
import com.singularity.todo.feature.projects.presentation.theme.ProjectIconRegistry
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.reminders.ReminderPicker
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * TickTick-style project detail screen with 4 sections:
 * 1. [ProjectHeroSection]     — color circle + icon + inline-edit name/desc + progress bar
 * 2. [ProjectMetaChipsRow]   — date, parent, child-count chips
 * 3. [ProjectBodySection]    — quick-add input + task list (≤5) + "See all" link
 * 4. [ProjectBottomActionBar] — remind/attach/more icon buttons
 *
 * All pickers/confirms are routed through [ActiveSheet].
 */

// ─── Public entry point — creates VM via Koin ──────────────────────────────────

/**
 * Shell that creates [ProjectDetailViewModel] via Koin and delegates to
 * [ProjectDetailContent]. This is the navigation-entry composable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalTime::class)
@Suppress("VIEW_MODEL_IN_COMPOSABLE") // Koin DSL — not a direct constructor call
@Composable
fun ProjectDetailScreen(
    projectId: ProjectId,
    modifier: Modifier = Modifier,
) {
    val nav = LocalProjectsNavigator.current
    val viewModel: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) }
    ProjectDetailContent(
        viewModel = viewModel,
        projectId = projectId,
        modifier = modifier,
    )
}

// ─── Content — accepts VM as parameter (usable without Koin) ──────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalTime::class)
@Composable
fun ProjectDetailContent(viewModel: ProjectDetailViewModel, projectId: ProjectId, modifier: Modifier = Modifier) {
    val nav = LocalProjectsNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastEditedAt by viewModel.lastEditedAt.collectAsStateWithLifecycle()
    val hideCompleted by viewModel.hideCompleted.collectAsStateWithLifecycle()
    val clock: Clock = Clock.System
    val parentOptions by viewModel.parentOptionsFlow.collectAsStateWithLifecycle()
    val availableTasks by viewModel.availableTasksFlow.collectAsStateWithLifecycle()
    var sheetState by remember { mutableStateOf<ActiveSheet?>(null) }
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val draftState by viewModel.draftState.state.collectAsStateWithLifecycle()

    // Routing + domain dispatcher — routing handled here, domain delegated to VM.
    val actions = remember {
        ProjectDetailActions { intent ->
            when (intent) {
                is ProjectDetailIntent.Routing.OpenColorSheet -> {
                    sheetState = ActiveSheet.PickColor
                }

                is ProjectDetailIntent.Routing.OpenIconSheet -> {
                    sheetState = ActiveSheet.PickIcon
                }

                is ProjectDetailIntent.Routing.OpenParentSheet -> {
                    sheetState = ActiveSheet.PickParent(
                    intent.currentParentId,
                )
                }

                is ProjectDetailIntent.Routing.OpenDueDateSheet -> {
                    sheetState = ActiveSheet.PickDueDate
                }

                is ProjectDetailIntent.Routing.OpenChildrenSheet -> {
                    sheetState = ActiveSheet.ShowChildren
                }

                is ProjectDetailIntent.Routing.OpenDeleteSheet -> {
                    sheetState = ActiveSheet.ConfirmDelete
                }

                is ProjectDetailIntent.Routing.OpenArchiveSheet -> {
                    sheetState = ActiveSheet.ConfirmArchive
                }

                is ProjectDetailIntent.Routing.OpenReminderSheet -> {
                    sheetState = ActiveSheet.PickReminder
                }

                is ProjectDetailIntent.Routing.OpenAttachmentSheet -> {
                    sheetState = ActiveSheet.AddAttachment
                }

                is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
            }
        }
    }

    // ShowError snackbar requires suspend — use LaunchedEffect directly.
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
                    // Edit icon removed — name is already inline-editable in ProjectHeroSection.
                    // Overflow menu with Archive / Delete.
                    Box {
                        IconButton(onClick = { overflowMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                        ) {
                            val isArchived = (state as? ProjectDetailUiState.Content)?.ui?.project?.isDeleted == true
                            DropdownMenuItem(
                                text = { Text(if (isArchived) "Unarchive" else "Archive") },
                                onClick = {
                                    overflowMenuOpen = false
                                    actions.onOpenArchiveSheet()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Delete",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
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
                ProjectBottomActionBar(
                    isArchived = contentState.ui.project.isDeleted,
                    actions = actions,
                )
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                ProjectHeroSection(
                    ui = s.ui,
                    lastEditedAt = lastEditedAt,
                    now = clock.now(),
                    nameDraft = draftState.name,
                    descriptionDraft = draftState.description,
                    actions = actions,
                )
                ProjectMetaChipsRow(
                    ui = s.ui,
                    actions = actions,
                )
                ProjectBodySection(
                    ui = s.ui,
                    hideCompleted = hideCompleted,
                    availableTasks = availableTasks,
                    actions = actions,
                    nav = nav,
                )
            }
        }
    }

    // ─── Sheets ───────────────────────────────────────────────────────────────

    if (sheetState != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetState = null },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        ) {
            when (sheetState) {
                null -> Unit

                is ActiveSheet.PickColor -> ColorPickerSheet(
                    currentColor = (state as? ProjectDetailUiState.Content)?.ui?.project?.color
                        ?: ProjectColorPalette.default,
                    onPick = { color ->
                        actions.onUpdateColor(color)
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.PickIcon -> IconPickerSheet(
                    currentIcon = (state as? ProjectDetailUiState.Content)?.ui?.project?.icon,
                    onPick = { icon ->
                        actions.onUpdateIcon(icon)
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.PickParent -> ParentPickerSheet(
                    options = parentOptions,
                    onPick = { parentId ->
                        actions.onUpdateParent(parentId)
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.ConfirmDelete -> ConfirmDeleteSheet(
                    projectName = (state as? ProjectDetailUiState.Content)?.ui?.project?.name ?: "",
                    onConfirm = {
                        actions.onDelete()
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.ConfirmArchive -> ConfirmArchiveSheet(
                    isArchived = (state as? ProjectDetailUiState.Content)?.ui?.project?.isDeleted == true,
                    onConfirm = {
                        actions.onToggleArchive()
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.PickReminder -> ReminderPickerSheet(
                    onPick = {
                        /* reminder set on project — future enhancement */ sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.AddAttachment -> AttachmentPlaceholderSheet(
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.PickDueDate -> DatePickerSheet(
                    initialDate = (state as? ProjectDetailUiState.Content)?.ui?.project?.dueDate,
                    onDateSelected = { date ->
                        actions.onUpdateDueDate(date)
                        sheetState = null
                    },
                    onDismiss = { sheetState = null },
                )

                is ActiveSheet.ShowChildren -> ChildProjectsSheet(
                    children = (state as? ProjectDetailUiState.Content)?.ui?.childProjects ?: emptyList(),
                    onDismiss = { sheetState = null },
                )
            }
        }
    }
}

// ─── 4 Sections ─────────────────────────────────────────────────────────────

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Color circle with icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(ui.project.color))
                    .clickable(onClick = actions::onOpenColorSheet),
                contentAlignment = Alignment.Center,
            ) {
                val icon = ProjectIconRegistry.iconByKey(ui.project.icon) ?: Icons.Filled.Folder
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
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

        // Progress bar
        if (ui.totalCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LinearProgressIndicator(
                    progress = { ui.progressFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(ui.project.color),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${ui.completedCount}/${ui.totalCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Description
        BasicTextField(
            value = descriptionDraft,
            onValueChange = { actions.onUpdateDescription(it.ifBlank { null }) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (descriptionDraft.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.project.dueDate?.let { date ->
            FilterChip(
                selected = false,
                onClick = actions::onOpenDueDateSheet,
                label = { Text(date.toString()) },
                leadingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }

        ui.parent?.let { parent ->
            FilterChip(
                selected = false,
                onClick = { actions.onOpenParentSheet(parent.id) },
                label = { Text(parent.name) },
                leadingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }

        if (ui.childProjects.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = actions::onOpenChildrenSheet,
                label = { Text("${ui.childProjects.size} sub-projects") },
                leadingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                },
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
        // Quick-add input
        ProjectDetailQuickAddInput(
            availableTasks = availableTasks,
            actions = actions,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Task list header with hide toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tasks",
                style = MaterialTheme.typography.titleMedium,
            )
            if (ui.totalCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hideCompleted) "Show completed" else "Hide completed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = actions::onToggleHideCompleted),
                    )
                }
            }
        }

        // Task list (≤5)
        if (ui.tasks.isEmpty()) {
            EmptyState(
                title = "No tasks yet",
                modifier = Modifier.padding(32.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(ui.tasks, key = { it.id.value }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { nav.openTask(task.id) },
                        actions = TaskCardActions.Empty,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // "See all" link
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

@Composable
private fun ProjectBottomActionBar(isArchived: Boolean, actions: ProjectDetailActions) {
    BottomAppBar(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = actions::onOpenReminderSheet) {
            Icon(Icons.Filled.Notifications, "Remind")
        }
        IconButton(onClick = actions::onOpenAttachmentSheet) {
            Icon(Icons.Filled.Folder, "Attach")
        }
        Spacer(Modifier.weight(1f))
        if (isArchived) {
            IconButton(onClick = actions::onToggleArchive) {
                Icon(Icons.Filled.PushPin, "Unarchive")
            }
        }
        IconButton(onClick = actions::onOpenIconSheet) {
            Icon(Icons.Filled.MoreVert, "More")
        }
    }
}

// ─── Quick Add ────────────────────────────────────────────────────────────────

@Composable
private fun ProjectDetailQuickAddInput(
    availableTasks: List<Task>,
    actions: ProjectDetailActions,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var popupOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { popupOpen = true }) {
                Icon(Icons.Filled.Add, "Add existing task", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Add a task...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) {
                        actions.onCreateTask(text)
                        text = ""
                        focus.clearFocus()
                    }
                }),
            )
        }

        if (popupOpen) {
            AddExistingTaskPopup(
                tasks = availableTasks,
                query = query,
                onQueryChange = { query = it },
                onPick = { taskId ->
                    actions.onMoveTaskToProject(taskId)
                    popupOpen = false
                    query = ""
                },
                onDismiss = {
                    popupOpen = false
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
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

// ─── Sheets ──────────────────────────────────────────────────────────────────

private sealed interface ActiveSheet {
    data object PickColor : ActiveSheet
    data object PickIcon : ActiveSheet
    data class PickParent(val current: ProjectId?) : ActiveSheet
    data object ConfirmDelete : ActiveSheet
    data object ConfirmArchive : ActiveSheet
    data object PickReminder : ActiveSheet
    data object AddAttachment : ActiveSheet
    data object PickDueDate : ActiveSheet
    data object ShowChildren : ActiveSheet
}

@Composable
private fun ColorPickerSheet(currentColor: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Color", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProjectColorPalette.all.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .clickable { onPick(color) },
                ) {
                    if (color == currentColor) {
                        Icon(
                            Icons.Filled.Folder, // check icon placeholder
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IconPickerSheet(currentIcon: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Icon", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectIconRegistry.all.forEach { (key, icon) ->
                Icon(
                    icon,
                    contentDescription = key,
                    tint = if (key == currentIcon) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onPick(key) }
                        .background(
                            if (key == currentIcon) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            CircleShape,
                        )
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentPickerSheet(options: List<ParentOption>, onPick: (ProjectId?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Parent project", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { onPick(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("None (root)")
            }
            LazyColumn {
                items(options) { opt ->
                    FilterChip(
                        selected = opt.isCurrent,
                        onClick = { onPick(opt.id) },
                        label = { Text(opt.name) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfirmDeleteSheet(projectName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete project?") },
        text = { Text("\"$projectName\" will be deleted. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmArchiveSheet(isArchived: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isArchived) "Unarchive project?" else "Archive project?") },
        text = {
            Text(
                if (isArchived) "This will restore the project." else "Archived projects are hidden from the list.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isArchived) "Unarchive" else "Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPickerSheet(onPick: (kotlinx.datetime.LocalDate?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            ReminderPicker(
                selected = com.singularity.todo.core.reminders.ReminderOffset.AT_DUE,
                onSelect = { offset ->
                    // Project-level reminder is a future enhancement;
                    // for now, creating a task with this offset would be the UX path.
                    onDismiss()
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentPlaceholderSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Attachments", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "Attachments for projects are a future enhancement. " +
                    "Please use task-level attachments via TaskDetail.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildProjectsSheet(children: List<Project>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Sub-projects", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (children.isEmpty()) {
                Text(
                    "No sub-projects",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(children) { child ->
                        FilterChip(
                            selected = false,
                            onClick = onDismiss,
                            label = { Text(child.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── Pure formatter ───────────────────────────────────────────────────────────

/** Formats a "Saved X ago" relative timestamp. Must be testable without Compose. */
@OptIn(ExperimentalTime::class)
fun formatSavedRelative(now: Instant, lastEdited: Instant): String {
    val diffMs = now.toEpochMilliseconds() - lastEdited.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Saved just now"
        diffMs < 3_600_000 -> "Saved ${diffMs / 60_000}m ago"
        else -> "Saved ${diffMs / 3_600_000}h ago"
    }
}
