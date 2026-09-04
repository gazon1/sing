package com.singularity.todo.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.projects.components.ProjectCard
import com.singularity.todo.feature.projects.components.ProjectCardActions
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onNavigateToProject: (String) -> Unit,
    onNavigateToCreateProject: () -> Unit,
) {
    val viewModel: ProjectsViewModel = koinInject()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var dialogText by remember { mutableStateOf<String?>(null) }

    CollectEvents(viewModel.events) { event ->
        val t = when (event) {
            is UiEvent.ShowDialog -> event.text
            is UiEvent.ShowError -> event.message
            UiEvent.NavigateBack -> return@CollectEvents
        }
        dialogText = t
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Projects") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateProject) {
                Icon(Icons.Filled.Create, contentDescription = "Add Project")
            }
        },
    ) { padding ->
        ProjectsContent(
            state = state,
            modifier = Modifier.padding(padding),
            onNavigateToProject = onNavigateToProject,
            onDelete = viewModel::delete,
            onReviewClick = viewModel::reviewProject,
        )
    }

    ResultDialog(title = "Project Review", text = dialogText, onDismiss = { dialogText = null })
}

@Composable
private fun ProjectsContent(
    state: ProjectsUiState,
    modifier: Modifier = Modifier,
    onNavigateToProject: (String) -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
) {
    when (state) {
        is ProjectsUiState.Loading -> LoadingIndicator(modifier = modifier)
        is ProjectsUiState.Empty -> EmptyState(title = "No projects yet", modifier = modifier)
        is ProjectsUiState.Error -> EmptyState(title = "Error: ${state.message}", modifier = modifier)
        is ProjectsUiState.Content -> ProjectList(
            projects = state.projects,
            modifier = modifier.fillMaxSize(),
            onNavigateToProject = onNavigateToProject,
            onDelete = onDelete,
            onReviewClick = onReviewClick,
        )
    }
}

@Composable
private fun ProjectList(
    projects: List<Project>,
    modifier: Modifier = Modifier,
    onNavigateToProject: (String) -> Unit,
    onDelete: (ProjectId) -> Unit,
    onReviewClick: (Project) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(projects, key = { it.id.value }) { project ->
            ProjectCard(
                project = project,
                onClick = { onNavigateToProject(project.id.value) },
                actions = ProjectCardActions { action ->
                    when (action) {
                        ProjectCardActions.Action.Delete -> onDelete(project.id)
                        ProjectCardActions.Action.Review -> onReviewClick(project)
                    }
                },
            )
        }
    }
}
