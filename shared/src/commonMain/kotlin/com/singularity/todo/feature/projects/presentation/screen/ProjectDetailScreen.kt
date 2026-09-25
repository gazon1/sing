package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Navigation entry point for project detail.
 * Delegates to [ProjectDetailContent] which accepts the ViewModel as a parameter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("VIEW_MODEL_IN_COMPOSABLE")
@Composable
fun ProjectDetailScreen(projectId: ProjectId, modifier: Modifier = Modifier) {
    val viewModel: ProjectDetailViewModel = koinViewModel { parametersOf(projectId) }
    ProjectDetailContent(viewModel = viewModel, modifier = modifier)
}
