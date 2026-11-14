@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.model.ProjectDetailUi
import com.singularity.todo.feature.projects.presentation.screen.ProjectHeroSection
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfText
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The shared project-identity presentation.
 *
 * One project must show the same glyph wherever it appears, so every surface
 * draws the stored `Project.icon` through [ProjectIcon]. The assertions that
 * fail without this branch are the fallback tag, the menu row's one-line height
 * and its `Role.Button`; the rest are regression guards for behaviour the branch
 * inherits — the menu already drew the stored emoji, and opening a row was
 * already independent from its expander. The platform's glyph layout is
 * asserted by the Android `ProjectIconEmojiRenderTest`, because a desktop text
 * node is constrained by the icon box and cannot show whether the font wrapped
 * or split the sequence.
 *
 * Tagged `slow` + `slow:compose`: a Compose harness mounts a real window and
 * counts as slow by the tag convention.
 */
@Tag("slow")
@Tag("slow:compose")
class ProjectIdentityTest {
    @Test
    fun `a project without an icon draws the fallback folder`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(
                    icon = null,
                    tint = Color.Black,
                    size = 20.dp,
                    contentDescription = "Project icon",
                )
            }

            assertTagDisplayed(TestTags.PROJECT_ICON_FALLBACK)
            assertContentDescriptionDisplayed("Project icon")
        }

    @Test
    fun `a blank icon also draws the fallback folder`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(icon = Emoji("   "), tint = Color.Black, size = 20.dp)
            }

            assertTagDisplayed(TestTags.PROJECT_ICON_FALLBACK)
        }

    @Test
    fun `a stored glyph is not replaced by the fallback`() =
        runIsolatedComposeTest {
            setContent {
                Column {
                    ProjectIcon(icon = Emoji("🧑‍💻"), tint = Color.Black, size = 20.dp)
                    ProjectIcon(icon = Emoji("💼"), tint = Color.Black, size = 20.dp)
                }
            }

            assertTagNotExists(TestTags.PROJECT_ICON_FALLBACK)
        }

    @Test
    fun `the stored sequence reaches the text layer`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(icon = Emoji("🧑‍💻"), tint = Color.Black, size = 20.dp)
            }

            assertTextDisplayed("🧑‍💻")
        }

    /**
     * A legacy Material key is drawn as its own text, not converted and not dropped.
     *
     * `projects.icon` used to hold a `ProjectIconRegistry` key (`"Work"`). The
     * registry and the resolver are gone (ADR 2026-11-14), so the renderer's rule
     * is the whole contract: any non-blank value is drawn. This test states that
     * outcome where a reader changing the rule will see it, so the choice is not
     * rediscovered from a bug report — a legacy key is visible, not silently lost.
     */
    @Test
    fun `a legacy Material key is drawn as its own text`() =
        runIsolatedComposeTest {
            setContent {
                ProjectIcon(icon = Emoji("Work"), tint = Color.Black, size = 20.dp)
            }

            assertTextDisplayed("Work")
            assertTagNotExists(TestTags.PROJECT_ICON_FALLBACK)
        }

    @Test
    fun `a project card draws the stored emoji`() =
        runIsolatedComposeTest {
            setContent {
                ProjectCard(project = project(name = "Card", icon = Emoji("🧑‍💻")), onClick = {})
            }

            assertTextDisplayed("🧑‍💻")
        }

    @Test
    fun `the project detail hero draws the stored emoji`() =
        runIsolatedComposeTest {
            val project = project(name = "Detail", icon = Emoji("🧑‍💻"))
            setContent {
                ProjectHeroSection(
                    ui =
                        ProjectDetailUi(
                            project = project,
                            tasks = emptyList(),
                            totalCount = 0,
                            completedCount = 0,
                            childProjects = persistentListOf(),
                            parent = null,
                        ),
                    lastEditedAt = null,
                    now = NOW,
                    nameSource = OwnedText(project.name, revision = 0),
                    descriptionSource = OwnedText("", revision = 0),
                    actions = ProjectDetailActions {},
                )
            }

            assertTextDisplayed("🧑‍💻")
        }

    @Test
    fun `a menu row draws the project emoji, keeps one line and is one button`() =
        runIsolatedComposeTest(width = 200, height = 400) {
            val project = project(name = LONG_NAME, icon = Emoji("🧑‍💻"))
            setContent {
                ProjectTreeSection(
                    state = treeOf(node(project)),
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

    @Test
    fun `the row opens the project and the expander only toggles`() =
        runIsolatedComposeTest(width = 360, height = 400) {
            var opens = 0
            var toggles = 0
            val project = project(name = "Parent")
            setContent {
                ProjectTreeSection(
                    state = treeOf(node(project, hasChildren = true)),
                    onToggleExpanded = { toggles++ },
                    onOpenProject = { opens++ },
                )
            }

            clickTag(TestTags.menuProjectExpander(project.id.value))
            assertEquals(1, toggles, "the expander toggles the branch")
            assertEquals(0, opens, "the expander must not open the project")

            clickTag(TestTags.menuProjectNode(project.id.value))
            assertEquals(1, toggles, "opening the row must not toggle the branch")
            assertEquals(1, opens, "the row opens the project")
        }

    @Test
    fun `the expander describes the action it will take`() =
        runIsolatedComposeTest(width = 360, height = 400) {
            val collapsed = project(name = "Collapsed", id = "p-collapsed")
            val expanded = project(name = "Expanded", id = "p-expanded")
            setContent {
                Column {
                    ProjectTreeSection(
                        state = treeOf(node(collapsed, hasChildren = true, isExpanded = false)),
                        onToggleExpanded = {},
                        onOpenProject = {},
                    )
                    ProjectTreeSection(
                        state = treeOf(node(expanded, hasChildren = true, isExpanded = true)),
                        onToggleExpanded = {},
                        onOpenProject = {},
                    )
                }
            }

            assertContentDescriptionDisplayed("Expand Collapsed")
            assertContentDescriptionDisplayed("Collapse Expanded")
        }

    private fun treeOf(vararg nodes: ProjectTreeNode) =
        ProjectsTreeUiState.Loaded(nodes = persistentListOf(*nodes), sectionExpanded = true)

    private fun node(
        project: Project,
        hasChildren: Boolean = false,
        isExpanded: Boolean = false,
    ) = ProjectTreeNode(
        project = project,
        depth = 0,
        hasChildren = hasChildren,
        isExpanded = isExpanded,
    )

    private fun project(
        name: String,
        icon: Emoji? = null,
        id: String = "p-1",
    ) = Project(
        id = ProjectId.fromString(id),
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
