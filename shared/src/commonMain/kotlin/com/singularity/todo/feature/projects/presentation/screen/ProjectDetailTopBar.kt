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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState

/**
 * Top app bar for [ProjectDetailScreen].
 *
 * Exposed as a public composable so it can be used in tests and preview providers
 * without constructing a full [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel].
 *
 * On Android the shell owns the only bar, so this bar publishes its title and its
 * back arrow to the shell (see `overrideShell`); on desktop it draws its own.
 *
 * @param state       Current UI state — used to display the project title and archive state.
 * @param actions     Action handlers from [ProjectDetailActions].
 * @param onBack     Called when the back arrow is tapped.
 * @param modifier    Standard Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailTopBar(
    state: State<ProjectDetailUiState>,
    actions: ProjectDetailActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflowMenuOpen by remember { mutableStateOf(false) }
    val isArchived = (state.value as? ProjectDetailUiState.Content)?.ui?.project?.isDeleted == true

    TopAppBar(
        // The title is read where it is drawn, not captured at publication. On
        // Android the shell draws this text in the shell's own bar, in the
        // shell's own composition: a title captured when the bar published
        // carries one extra step between the state change and the redraw, and
        // on a cold start the bar could keep saying "Project" while the screen
        // behind it already showed the name.
        title = {
            Text(
                detailBarTitle(state.value),
                modifier = Modifier.testTag(TestTags.SHELL_BAR_TITLE),
            )
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestTags.TOP_BAR_BACK_BUTTON),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            Box {
                IconButton(
                    onClick = { overflowMenuOpen = true },
                    modifier = Modifier.testTag(TestTags.PROJECT_DETAIL_OVERFLOW),
                ) {
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
                        text = { Text("Close project") },
                        onClick = {
                            overflowMenuOpen = false
                            actions.onOpenCloseSheet()
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
        // On Android the shell owns the only bar, so this bar publishes its title
        // and its back arrow to the shell instead of drawing a second one. The
        // arrow is the screen's own: it pops the nested projects graph, which the
        // shell's arrow — it only knows the outer stack — cannot do.
        overrideShell = true,
    )
}

/**
 * The name the bar shows for [state].
 *
 * The project itself is the name; the other states have no name to show yet and
 * say what they are.
 */
private fun detailBarTitle(state: ProjectDetailUiState): String =
    when (state) {
        ProjectDetailUiState.Loading -> "Project"
        ProjectDetailUiState.NotFound -> "Not found"
        is ProjectDetailUiState.Error -> "Project"
        is ProjectDetailUiState.Content -> state.ui.project.name
    }
