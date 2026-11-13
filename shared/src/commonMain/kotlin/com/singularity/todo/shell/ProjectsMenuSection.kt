package com.singularity.todo.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.presentation.components.ProjectTreeSection
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState

/**
 * The menu's projects section: the projects, and the way to the full list.
 *
 * One section, drawn by both platforms: the Android sheet and the Desktop
 * drawer show the same heading, the same way into the full list, and the same
 * projects — but not the same *depth*, on purpose. The Desktop drawer draws the
 * tree, with its expanders; the Android sheet draws the root projects as a
 * list (ADR `2026-11-12-android-menu-roots-only.md`). The projects are one
 * model's; how much of the hierarchy a menu shows is that surface's decision.
 *
 * The heading, the label and the tag are the same on both surfaces, so
 * rewording one of them cannot leave the other behind.
 *
 * What the surfaces differ in is the padding around them, and that is what
 * the three modifiers are for: the sheet's column carries its own insets, and
 * the drawer's items carry theirs. Nothing here decides the metrics — the
 * caller does.
 *
 * ## The section is folded by default
 *
 * A profile with a hundred projects draws a list longer than the screen, and
 * the list sits below the entries the user opened the menu for. So the list is
 * folded away until the user unfolds it, and the way into the full list stays
 * next to the heading — folded or not.
 *
 * Stateless: the caller owns the ViewModel and hands the state over.
 *
 * @param state The nodes to draw.
 * @param onToggleSection Called when the user folds the section away or unfolds
 *   it.
 * @param onToggleExpanded Called with the id of the project whose expander was
 *   tapped. `null` draws the root projects as a list, without expanders — what
 *   the Android sheet asks for.
 * @param onOpenProject Called with the id of the project the user tapped.
 * @param onOpenAllProjects Called when the user taps the way to the full list.
 * @param headerModifier The modifier of the heading.
 * @param treeModifier The modifier of the tree.
 * @param itemModifier The modifier of the way to the full list.
 */
@Composable
fun ProjectsMenuSection(
    state: ProjectsTreeUiState,
    onToggleSection: () -> Unit,
    onToggleExpanded: ((ProjectId) -> Unit)?,
    onOpenProject: (ProjectId) -> Unit,
    onOpenAllProjects: () -> Unit,
    headerModifier: Modifier = Modifier,
    treeModifier: Modifier = Modifier,
    itemModifier: Modifier = Modifier,
) {
    Row(
        modifier =
            headerModifier
                .fillMaxWidth()
                .mapTestTagsAsResourceIds()
                .testTag(TestTags.MENU_PROJECTS_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Projects",
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(
            onClick = onToggleSection,
            modifier =
                Modifier
                    .mapTestTagsAsResourceIds()
                    // The button carries the tag of the action it offers, so a
                    // test can tell the folded section from the unfolded one
                    // without reading the tree.
                    .testTag(
                        if (state.sectionExpanded) {
                            TestTags.MENU_PROJECTS_COLLAPSE
                        } else {
                            TestTags.MENU_PROJECTS_EXPAND
                        },
                    ),
        ) {
            Icon(
                imageVector =
                    if (state.sectionExpanded) {
                        Icons.Default.ExpandMore
                    } else {
                        Icons.Default.ChevronRight
                    },
                contentDescription =
                    if (state.sectionExpanded) {
                        "Collapse projects"
                    } else {
                        "Expand projects"
                    },
            )
        }
    }

    // The way into the full list sits above the projects, so it is reachable
    // whether they are unfolded or not.
    NavigationDrawerItem(
        label = { Text("All projects") },
        selected = false,
        onClick = onOpenAllProjects,
        icon = {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "All projects",
            )
        },
        modifier =
            itemModifier
                .fillMaxWidth()
                .mapTestTagsAsResourceIds()
                .testTag(TestTags.MENU_ALL_PROJECTS),
    )

    if (state.sectionExpanded) {
        ProjectTreeSection(
            state = state,
            onToggleExpanded = onToggleExpanded,
            onOpenProject = onOpenProject,
            modifier = treeModifier,
        )
    }
}
