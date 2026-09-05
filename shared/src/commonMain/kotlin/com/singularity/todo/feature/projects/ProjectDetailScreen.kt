package com.singularity.todo.feature.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import org.koin.compose.koinInject

/**
 * Detail screen for a single project. Stateless — driven by
 * [ProjectDetailViewModel] which is started in [LaunchedEffect].
 *
 * Visual states:
 * - [ProjectDetailUiState.Loading] → spinner
 * - [ProjectDetailUiState.Empty] / [NotFound] → empty state
 * - [Content] → project name + description
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: ProjectId,
    onBack: () -> Unit,
    viewModel: ProjectDetailViewModel = koinInject(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackTopAppBar(
        title = "Project",
        onBack = onBack,
        modifier = Modifier.testTag(TestTags.PROJECT_DETAIL_TOP_BAR),
    ) { padding ->
        when (val s = state) {
            ProjectDetailUiState.Loading -> LoadingIndicator(modifier = Modifier.padding(padding))
            ProjectDetailUiState.Empty,
            ProjectDetailUiState.NotFound -> EmptyState(
                title = "Project not found",
                modifier = Modifier.padding(padding),
            )
            is ProjectDetailUiState.Content -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(s.project.name, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                s.project.description?.let { Text(it) }
            }
        }
    }
}
