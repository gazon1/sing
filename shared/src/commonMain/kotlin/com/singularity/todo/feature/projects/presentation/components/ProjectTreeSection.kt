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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState

/**
 * The menu's project tree.
 *
 * One composable, drawn by both platforms — and the two drawings are not the
 * same, on purpose: the Desktop drawer draws the tree with its expanders, and
 * the Android sheet draws the **root projects as a flat list** (ADR
 * `2026-11-12-android-menu-roots-only.md`). One tree model
 * ([ProjectTreeBuilder]) and one composable, so a project is the same object
 * wherever it is drawn; the platforms differ in how much of the hierarchy the
 * menu shows, not in what the hierarchy is.
 *
 * The tree is drawn as a flat list of rows rather than as nested rows, because
 * the menu's column scrolls and a scroll cannot cross into a nested list.
 *
 * Stateless: the caller owns the ViewModel and hands the state over. The
 * Android sheet and the Desktop drawer each resolve the ViewModel in their own
 * composition — where the Koin context is certain — and both draw the tree
 * with this one composable.
 *
 * ## A list of roots is not a tree
 *
 * [onToggleExpanded] is the one switch between the two drawings. The Desktop
 * drawer passes it, so a project with children offers an expander. The Android
 * sheet passes `null`: a list has no expander, so its rows carry no hierarchy
 * — and a hierarchy that is not drawn is not there, so the list holds the
 * **root projects only**. A child is reached from its parent's own screen.
 *
 * @param state The nodes to draw.
 * @param onToggleExpanded Called with the id of the project whose expander was
 *   tapped. `null` draws the root projects as a list, without expanders.
 * @param onOpenProject Called with the id of the project the user tapped. The
 *   tree is a way into the project, not a summary of it.
 */
@Composable
fun ProjectTreeSection(
    state: ProjectsTreeUiState,
    onToggleExpanded: ((ProjectId) -> Unit)?,
    onOpenProject: (ProjectId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TestTags.MENU_PROJECTS_SECTION),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (state) {
            is ProjectsTreeUiState.Loading -> {
                Unit
            }

            is ProjectsTreeUiState.Loaded -> {
                // Without expanders there is no hierarchy to show, so the list
                // holds the roots: the children are behind their parent, and
                // the parent is where they are reached from.
                val nodes =
                    if (onToggleExpanded == null) {
                        state.nodes.filter { it.depth == 0 }
                    } else {
                        state.nodes
                    }
                for (node in nodes) {
                    ProjectTreeRow(
                        node = node,
                        onToggleExpanded = onToggleExpanded,
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
 *
 * A row without an expander is not a row with an empty one: where the tree
 * keeps the expander's place so that the names of a level line up, the list of
 * roots has no expander column at all, and its names start at the row's edge.
 */
@Composable
private fun ProjectTreeRow(
    node: ProjectTreeNode,
    onToggleExpanded: ((ProjectId) -> Unit)?,
    onOpen: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // One level of indent per step down the hierarchy.
                .padding(start = (node.depth * 16).dp)
                // The whole row opens the project, and the row is what carries the
                // tag: a click on the row's centre is the click a test makes, and a
                // clickable only on the name would miss it.
                .clickable(role = Role.Button, onClick = onOpen)
                .testTag(TestTags.menuProjectNode(node.id.value)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggleExpanded != null) {
            if (node.hasChildren) {
                IconButton(
                    onClick = { onToggleExpanded(node.id) },
                    modifier = Modifier.testTag(TestTags.menuProjectExpander(node.id.value)),
                ) {
                    Icon(
                        imageVector =
                            if (node.isExpanded) {
                                Icons.Default.ExpandMore
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                        contentDescription =
                            if (node.isExpanded) {
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
        }

        ProjectIcon(
            icon = node.project.icon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
            // The row's title is the accessible name; a described icon would
            // announce the same project twice.
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
        )

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
