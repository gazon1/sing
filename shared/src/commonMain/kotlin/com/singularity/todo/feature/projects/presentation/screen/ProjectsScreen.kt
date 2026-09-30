package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts
import com.singularity.todo.feature.projects.presentation.components.ProjectCard
import com.singularity.todo.feature.projects.presentation.components.ProjectCardActions
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsPreviewWrapper
import com.singularity.todo.feature.projects.presentation.state.ProjectSortOrder
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen() {
    val nav = LocalProjectsNavigator.current
    val viewModel: ProjectsViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val content = state as? ProjectsUiState.Content
    val searchQuery = content?.searchQuery.orEmpty()
    val sortOrder = content?.sortOrder ?: ProjectSortOrder.Name

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Projects") })
                ProjectSearchAndSortRow(
                    query = searchQuery,
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
            searchQuery = searchQuery,
            onNavigateToProject = { id -> nav.openDetail(ProjectId.fromString(id)) },
            onCreateProject = { nav.openEditor(null) },
            onDelete = { id -> viewModel.onIntent(ProjectsIntent.Delete(id)) },
            onReviewClick = { project -> viewModel.onIntent(ProjectsIntent.ReviewProject(project)) },
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
    query: String,
    sortOrder: ProjectSortOrder,
    onQueryChange: (String) -> Unit,
    onSortOrderChange: (ProjectSortOrder) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search projects") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
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
    searchQuery: String = "",
    onNavigateToProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
) {
    when (state) {
        is ProjectsUiState.Loading -> LoadingIndicator(modifier = modifier)

        // A search that matched nothing is not the same as having no projects —
        // "No projects yet" would push the user to create one they do not need.
        is ProjectsUiState.Empty -> EmptyState(
            title = if (searchQuery.isBlank()) "No projects yet" else "No projects match \"$searchQuery\"",
            modifier = modifier,
        )

        is ProjectsUiState.Error -> EmptyState(title = "Error: ${state.message}", modifier = modifier)

        is ProjectsUiState.Content -> ProjectList(
            projects = state.projects,
            modifier = modifier.fillMaxSize(),
            onNavigateToProject = onNavigateToProject,
            onCreateProject = onCreateProject,
            onDelete = onDelete,
            onReviewClick = onReviewClick,
        )
    }
}

@Composable
private fun ProjectList(
    projects: List<ProjectWithCounts>,
    modifier: Modifier = Modifier,
    onNavigateToProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(projects, key = { it.project.id.value }) { row ->
            ProjectCard(
                project = row.project,
                totalCount = row.totalCount,
                completedCount = row.completedCount,
                onClick = { onNavigateToProject(row.project.id.value) },
                actions = ProjectCardActions { action ->
                    when (action) {
                        ProjectCardActions.Action.Delete -> onDelete(row.project.id)
                        ProjectCardActions.Action.Review -> onReviewClick(row.project)
                    }
                },
            )
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
                projects = listOf(
                    ProjectWithCounts(PreviewSamples.project("p1", "Inbox", 0xFF2196F3.toInt()), 5, 2),
                    ProjectWithCounts(PreviewSamples.project("p2", "Work", 0xFFF44336.toInt()), 12, 8),
                    ProjectWithCounts(PreviewSamples.project("p3", "Personal", 0xFF9C27B0.toInt()), 0, 0),
                ),
            ),
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
        )
    }
}

@Preview
@Composable
private fun ProjectsScreenEmptyPreview() = ProjectsPreviewWrapper {
    PreviewThemed(darkTheme = false, useSurface = false) {
        ProjectsContent(
            state = ProjectsUiState.Empty,
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
        )
    }
}

@Preview
@Composable
private fun ProjectsScreenDarkPreview() = ProjectsPreviewWrapper {
    PreviewThemed(darkTheme = true, useSurface = false) {
        ProjectsContent(
            state = ProjectsUiState.Content(
                projects = listOf(
                    ProjectWithCounts(PreviewSamples.project("p1", "Archived", 0xFF607D8B.toInt()), 3, 1),
                ),
            ),
            onNavigateToProject = {},
            onCreateProject = {},
            onDelete = {},
            onReviewClick = {},
        )
    }
}
