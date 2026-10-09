package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState

/**
 * Top app bar for [ProjectDetailScreen].
 *
 * Exposed as a public composable so it can be used in tests and preview providers
 * without constructing a full [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel].
 *
 * @param state       Current UI state — used to display the project title and archive state.
 * @param actions     Action handlers from [ProjectDetailActions].
 * @param onBack     Called when the back arrow is tapped.
 * @param modifier    Standard Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailTopBar(
    state: ProjectDetailUiState,
    actions: ProjectDetailActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val isArchived = (state as? ProjectDetailUiState.Content)?.ui?.project?.isDeleted == true
    val title = when (state) {
        ProjectDetailUiState.Loading -> "Project"
        ProjectDetailUiState.NotFound -> "Not found"
        is ProjectDetailUiState.Content -> state.ui.project.name
    }

    TopAppBar(
        title = { Text(title) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
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
                    // Renders in its own popup window — mapTestTagsAsResourceIds required
                    // so Maestro id: selectors can find items inside the menu.
                    modifier = Modifier.mapTestTagsAsResourceIds(),
                ) {
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
}
