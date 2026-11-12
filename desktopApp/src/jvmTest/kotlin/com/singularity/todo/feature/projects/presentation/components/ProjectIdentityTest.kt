@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The shared project-identity presentation.
 *
 * One project must show the same glyph wherever it appears, so every surface
 * draws the stored `Project.icon` through [ProjectIcon]. These tests pin the
 * contract itself: the fallback, a composed emoji, the compact row's one-line
 * policy and the row's accessible role.
 *
 * Tagged `slow` + `slow:compose`: a Compose harness mounts a real window and
 * counts as slow by the tag convention.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectIdentityTest {
    @Test
    fun `a project without an icon falls back to the folder`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(
                    icon = null,
                    tint = Color.Black,
                    size = 20.dp,
                    contentDescription = "Project icon",
                )
            }

            assertContentDescriptionDisplayed("Project icon")
        }

    @Test
    fun `a composed emoji is drawn as one sequence`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(icon = "🧑‍💻", tint = Color.Black, size = 20.dp)
            }

            assertTextDisplayed("🧑‍💻")
        }

    @Test
    fun `a menu row draws the project emoji, keeps one line and is one button`() =
        runIsolatedComposeTest(width = 200, height = 400) {
            val project = project(name = LONG_NAME, icon = "🧑‍💻")
            setContent {
                ProjectTreeSection(
                    state =
                        ProjectsTreeUiState.Loaded(
                            nodes =
                                persistentListOf(
                                    ProjectTreeNode(
                                        project = project,
                                        depth = 0,
                                        hasChildren = false,
                                        isExpanded = false,
                                    ),
                                ),
                            sectionExpanded = true,
                        ),
                    onToggleExpanded = {},
                    onOpenProject = {},
                )
            }

            assertTextDisplayed(LONG_NAME)
            assertTextDisplayed("🧑‍💻")

            val title = unclippedBoundsOfText(LONG_NAME)
            assertTrue(
                title.height() < 30.dp,
                "a compact project row keeps its title on one line, got ${title.height()}",
            )

            awaitTag(TestTags.menuProjectNode(project.id.value))
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }

    private fun project(
        name: String,
        icon: String?,
    ) = Project(
        id = ProjectId.fromString("p-1"),
        name = name,
        color = 0xFF3366FF.toInt(),
        icon = icon,
        createdAt = NOW,
        updatedAt = NOW,
        userId = UserId("user-a"),
    )

    private fun DpRect.height(): Dp = bottom - top

    private companion object {
        const val LONG_NAME =
            "A project name long enough that it would wrap several times in a compact row"

        val NOW: Instant = Instant.parse("2026-11-12T12:00:00Z")
    }
}
