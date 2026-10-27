package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState

/**
 * The menu's project tree.
 *
 * One composable, drawn by both platforms: the Android sheet and the Desktop
 * drawer show the same projects, in the same order, at the same depths. Two
 * drawings of one tree would be two features, and the parity between them
 * would depend on somebody noticing the difference.
 *
 * The tree is drawn as a flat list of rows rather than as nested rows, because
 * the menu's column scrolls and a scroll cannot cross into a nested list.
 *
 * Stateless: the caller owns the ViewModel and hands the state over. The
 * Android sheet and the Desktop drawer each resolve the ViewModel in their own
 * composition — where the Koin context is certain — and both draw the tree
 * with this one composable.
 *
 * @param state The nodes to draw.
 * @param onToggleExpanded Called with the id of the project whose expander was
 *   tapped.
 * @param onOpenProject Called with the id of the project the user tapped. The
 *   tree is a way into the project, not a summary of it.
 */
@Composable
fun ProjectTreeSection(
    state: ProjectsTreeUiState,
    onToggleExpanded: (ProjectId) -> Unit,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.MENU_PROJECTS_SECTION),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (state) {
            is ProjectsTreeUiState.Loading -> Unit

            is ProjectsTreeUiState.Loaded -> {
                for (node in state.nodes) {
                    ProjectTreeRow(
                        node = node,
                        onToggleExpanded = { onToggleExpanded(node.id) },
                        onOpen = { onOpenProject(node.id) },
                    )
                }
            }
        }
    }
}

/**
 * One project in the tree.
 *
 * The indent is the assertion of the whole feature: a child sits under its
 * parent, and a flat list of names would lose the one thing a tree shows.
 */
@Composable
private fun ProjectTreeRow(
    node: ProjectTreeNode,
    onToggleExpanded: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One level of indent per step down the hierarchy.
            .padding(start = (node.depth * 16).dp)
            // The whole row opens the project, and the row is what carries the
            // tag: a click on the row's centre is the click a test makes, and a
            // clickable only on the name would miss it.
            .clickable(onClick = onOpen)
            .testTag(TestTags.menuProjectNode(node.id.value)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.hasChildren) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.testTag(TestTags.menuProjectExpander(node.id.value)),
            ) {
                Icon(
                    imageVector = if (node.isExpanded) {
                        Icons.Default.ExpandMore
                    } else {
                        Icons.Default.ChevronRight
                    },
                    contentDescription = if (node.isExpanded) {
                        "Collapse ${node.name}"
                    } else {
                        "Expand ${node.name}"
                    },
                )
            }
        } else {
            // The expander's place, kept empty — so the names of a level line up
            // whether or not the projects on it have children.
            Spacer(modifier = Modifier.width(48.dp))
        }

        ProjectIcon(
            icon = node.project.icon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
            contentDescription = node.name,
            modifier = Modifier.padding(start = 4.dp),
        )

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
