@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The two drawings of the project section.
 *
 * One composable draws both, and the switch is a single argument: `null` means
 * "no expanders", which means "no hierarchy", which means the list holds the
 * **root projects only** (ADR `2026-11-12-android-menu-roots-only.md`). Android
 * passes `null`; the Desktop drawer passes a toggle lambda and keeps the tree.
 *
 * The assertions that fail without the branch are the ones about the list: the
 * child is not drawn, and no expander is offered. The tree's own drawing is
 * asserted here as well, so the two modes cannot drift apart.
 *
 * Tagged `slow` + `slow:compose`: a Compose harness mounts a real window and
 * counts as slow by the tag convention.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectTreeSectionModeTest {
    @Test
    fun `the list of roots does not draw the children`() =
        runIsolatedComposeTest(width = 360, height = 600) {
            val parent = project(name = "Parent", id = "p-parent")
            val child = project(name = "Child", id = "p-child")
            setContent {
                ProjectTreeSection(
                    state = treeOf(node(parent, depth = 0, hasChildren = true), node(child, depth = 1)),
                    onToggleExpanded = null,
                    onOpenProject = {},
                )
            }

            assertTagDisplayed(TestTags.menuProjectNode(parent.id.value))
            assertTagNotExists(TestTags.menuProjectNode(child.id.value))
        }

    @Test
    fun `the list of roots offers no expander`() =
        runIsolatedComposeTest(width = 360, height = 600) {
            val parent = project(name = "Parent", id = "p-parent")
            setContent {
                ProjectTreeSection(
                    state = treeOf(node(parent, depth = 0, hasChildren = true)),
                    onToggleExpanded = null,
                    onOpenProject = {},
                )
            }

            assertTagNotExists(TestTags.menuProjectExpander(parent.id.value))
        }

    @Test
    fun `the tree draws the children and their expander`() =
        runIsolatedComposeTest(width = 360, height = 600) {
            val parent = project(name = "Parent", id = "p-parent")
            val child = project(name = "Child", id = "p-child")
            setContent {
                ProjectTreeSection(
                    state = treeOf(node(parent, depth = 0, hasChildren = true), node(child, depth = 1)),
                    onToggleExpanded = {},
                    onOpenProject = {},
                )
            }

            assertTagDisplayed(TestTags.menuProjectNode(child.id.value))
            awaitTag(TestTags.menuProjectExpander(parent.id.value))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }

    private fun treeOf(vararg nodes: ProjectTreeNode) =
        ProjectsTreeUiState.Loaded(nodes = persistentListOf(*nodes), sectionExpanded = true)

    private fun node(
        project: Project,
        depth: Int,
        hasChildren: Boolean = false,
    ) = ProjectTreeNode(
        project = project,
        depth = depth,
        hasChildren = hasChildren,
        isExpanded = true,
    )

    private fun project(
        name: String,
        id: String,
    ) = Project(
        id = ProjectId.fromString(id),
        name = name,
        color = 0xFF3366FF.toInt(),
        icon = null,
        createdAt = NOW,
        updatedAt = NOW,
        userId = UserId("user-a"),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")
    }
}
