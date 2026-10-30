package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.refresh.RefreshableContent
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ReopenActionButton
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.components.ContentState
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.text.StaticText
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.ImmutableList
import com.singularity.todo.feature.projects.presentation.components.ProjectCard
import com.singularity.todo.feature.projects.presentation.components.ProjectCardActions
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsPreviewWrapper
import com.singularity.todo.feature.projects.presentation.state.ProjectSortOrder
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiState
import com.singularity.todo.feature.projects.presentation.state.searchQuerySource
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen() {
    val nav = LocalProjectsNavigator.current
    val viewModel: ProjectsViewModel = koinViewModel()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val searchQuerySource = state.searchQuerySource
    val sortOrder = state.sortOrder

    var pendingDeleteId by remember { mutableStateOf<ProjectId?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Projects") })
                ProjectSearchAndSortRow(
                    querySource = searchQuerySource,
                    sortOrder = sortOrder,
                    onQueryChange = { viewModel.onIntent(ProjectsIntent.SetSearchQuery(it)) },
                    onSortOrderChange = { viewModel.onIntent(ProjectsIntent.SetSortOrder(it)) },
                )
            }
        },
    ) { padding ->
        ProjectsContent(
            state = state,
            modifier = Modifier.padding(padding),
            onNavigateToProject = { nav.openDetail(it) },
            onCreateProject = { nav.openEditor(null) },
            onDelete = { id -> pendingDeleteId = id },
            onReviewClick = { project -> viewModel.onIntent(ProjectsIntent.ReviewProject(project)) },
            onReopen = { id -> viewModel.onIntent(ProjectsIntent.Reopen(id)) },
            onRetry = { viewModel.onIntent(ProjectsIntent.Retry) },
        )
    }

    pendingDeleteId?.let { id ->
        ConfirmActionDialog(
            title = "Delete project?",
            text = "This action cannot be undone.",
            confirmButtonText = "Delete",
            onConfirm = {
                viewModel.onIntent(ProjectsIntent.Delete(id))
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag(TestTags.PROJECTS_NOTIFICATION_HOST),
    )
}

/**
 * Project name filter + sort chips.
 *
 * Both read from [ProjectsUiState.Content] rather than owning local state, so the
 * ViewModel stays the single source of truth: the chips highlight the order the list
 * is actually rendered in, not the one last tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSearchAndSortRow(
    querySource: StaticText,
    sortOrder: ProjectSortOrder,
    onQueryChange: (String) -> Unit,
    onSortOrderChange: (ProjectSortOrder) -> Unit,
) {
    val queryState = rememberBridgedTextFieldState(
        source = querySource,
        onTextChanged = onQueryChange,
    )
    Column {
        OutlinedTextField(
            state = queryState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(TestTags.PROJECTS_SEARCH_INPUT),
            placeholder = { Text("Search projects") },
            lineLimits = TextFieldLineLimits.SingleLine,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (queryState.text.isNotEmpty()) {
                    IconButton(onClick = { queryState.clearText() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortOrderChange(order) },
                    label = { Text(order.label) },
                )
            }
        }
    }
}

private val ProjectSortOrder.label: String
    get() = when (this) {
        ProjectSortOrder.Name -> "Name"
        ProjectSortOrder.Color -> "Color"
    }

private fun ProjectsUiEvent.toNotification(): Notification = when (this) {
    is ProjectsUiEvent.ProjectReviewResult -> Notification.Text(title = "Project Review", text = text)
    is ProjectsUiEvent.Error -> Notification.Error(message)
}

@Composable
private fun ProjectsContent(
    state: ProjectsUiState,
    modifier: Modifier = Modifier,
    onNavigateToProject: (ProjectId) -> Unit,
    onCreateProject: () -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
    onReopen: (ProjectId) -> Unit,
    onRetry: () -> Unit = {},
) {
    RefreshableContent {
        when (state) {
            is ProjectsUiState.Loading -> LoadingIndicator(modifier = modifier)

            // A search that matched nothing is not the same as having no projects —
            // "No projects yet" would push the user to create one they do not need.
            //
            // The closed projects are still there: the list is empty, not the
            // account.
            is ProjectsUiState.Empty -> Column(modifier = modifier.fillMaxSize()) {
                EmptyState(
                    title = if (state.searchQuery.text.isBlank()) {
                        "No projects yet"
                    } else {
                        "No projects match \"${state.searchQuery.text}\""
                    },
                    testTag = TestTags.PROJECTS_LIST_EMPTY,
                    modifier = Modifier.weight(1f),
                )
                ClosedProjectsSection(closedProjects = state.closedProjects, onReopen = onReopen)
            }

            is ProjectsUiState.Error -> StatefulContent(
                state = ContentState.Error(AppError.Unknown(state.message, code = "projects.load_failed")),
                emptyTitle = "",
                onRetry = onRetry,
                modifier = modifier,
            ) { _, _ -> }

            is ProjectsUiState.Content -> ProjectList(
                projects = state.projects,
                closedProjects = state.closedProjects,
                modifier = modifier.fillMaxSize(),
                onNavigateToProject = onNavigateToProject,
                onCreateProject = onCreateProject,
                onDelete = onDelete,
                onReviewClick = onReviewClick,
                onReopen = onReopen,
            )
        }
    }
}

/**
 * The projects the user closed, and the way back.
 *
 * A closed project leaves the list, and without this section there would be
 * nowhere to find it again: the only way back would be through one of its
 * tasks, and a project with no tasks has none.
 */
@Composable
private fun ClosedProjectsSection(
    closedProjects: ImmutableList<Project>,
    onReopen: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (closedProjects.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Closed projects",
            style = MaterialTheme.typography.titleSmall,
        )
        closedProjects.forEach { project ->
            ClosedProjectRow(project = project, onReopen = { onReopen(project.id) })
        }
    }
}

/**
 * One closed project, and the button that opens it again.
 *
 * Not a [ProjectCard]: a closed project is not on the list, and a card that
 * navigates somewhere the user did not ask to go is the wrong affordance. This
 * row says what the project is and offers the way back.
 */
@Composable
private fun ClosedProjectRow(project: Project, onReopen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = project.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        ReopenActionButton(onClick = onReopen)
    }
}

@Composable
private fun ProjectList(
    projects: ImmutableList<ProjectWithCounts>,
    closedProjects: ImmutableList<Project>,
    modifier: Modifier = Modifier,
    onNavigateToProject: (ProjectId) -> Unit,
    onCreateProject: () -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
    onReopen: (ProjectId) -> Unit,
) {
    LazyColumn(
        modifier = modifier.widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(projects, key = { it.project.id.value }) { row ->
            ProjectCard(
                project = row.project,
                totalCount = row.totalCount,
                completedCount = row.completedCount,
                onClick = { onNavigateToProject(row.project.id) },
                actions = ProjectCardActions { action ->
                    when (action) {
                        ProjectCardActions.Action.Delete -> onDelete(row.project.id)
                        ProjectCardActions.Action.Review -> onReviewClick(row.project)
                        ProjectCardActions.Action.Reopen -> onReopen(row.project.id)
                    }
                },
            )
        }
        if (closedProjects.isNotEmpty()) {
            item(key = "closed-projects-header") {
                Text(
                    text = "Closed projects",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(closedProjects, key = { "closed-${it.id.value}" }) { project ->
                ClosedProjectRow(project = project, onReopen = { onReopen(project.id) })
            }
        }
    }
}

// ===== Preview =====

@Preview
@Composable
private fun ProjectsScreenContentPreview() = ProjectsPreviewWrapper {
    PreviewThemed(darkTheme = false, useSurface = false) {
        ProjectsContent(
            state = ProjectsUiState.Content(
                projects = persistentListOf(
                    ProjectWithCounts(PreviewSamples.project("p1", "Inbox", 0xFF2196F3.toInt()), 5, 2),
                    ProjectWithCounts(PreviewSamples.project("p2", "Work", 0xFFF44336.toInt()), 12, 8),
                    ProjectWithCounts(PreviewSamples.project("p3", "Personal", 0xFF9C27B0.toInt()), 0, 0),
                ),
                searchQuery = FieldOwnedText.empty(),
                sortOrder = ProjectSortOrder.Name,
                closedProjects = persistentListOf(),
            ),
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
            onReopen = {},
        )
    }
}

@Preview
@Composable
private fun ProjectsScreenEmptyPreview() = ProjectsPreviewWrapper {
    PreviewThemed(darkTheme = false, useSurface = false) {
        ProjectsContent(
            state = ProjectsUiState.Empty(
                searchQuery = FieldOwnedText.empty(),
                sortOrder = ProjectSortOrder.Name,
                closedProjects = persistentListOf(),
            ),
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
            onReopen = {},
        )
    }
}

@Preview
@Composable
private fun ProjectsScreenDarkPreview() = ProjectsPreviewWrapper {
    PreviewThemed(darkTheme = true, useSurface = false) {
        ProjectsContent(
            state = ProjectsUiState.Content(
                projects = persistentListOf(
                    ProjectWithCounts(PreviewSamples.project("p1", "Archived", 0xFF607D8B.toInt()), 3, 1),
                ),
                searchQuery = FieldOwnedText.empty(),
                sortOrder = ProjectSortOrder.Name,
                closedProjects = persistentListOf(),
            ),
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
            onReopen = {},
        )
    }
}
