package com.singularity.todo.feature.flows.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.DestinationKind
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.presentation.components.ProjectTreeSection
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeUiState
import com.singularity.todo.shell.AppMenu
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The menu of the two platforms is one menu.
 *
 * The Android sheet and the Desktop drawer are drawn by different code, so
 * "the same menu on both platforms" has to be a fact rather than a promise.
 * It is a fact because both draw [AppMenu] — the model — and the same tree
 * composable.
 *
 * The assertion is on the *set* of destinations, not on the order: the sheet
 * groups its entries into Account, Search and Destinations, and a drawer shows
 * one column. The entries are the same; the layout is not.
 *
 * The projects are the other half of the menu, and they are a fact for the
 * same reason: both surfaces draw the same section from the same state, and
 * the one switch between the two drawings is the expander — the sheet draws
 * the roots, the drawer draws the tree (ADR
 * `2026-11-12-android-menu-roots-only.md`). The roots the two surfaces offer
 * are therefore the roots of one state, and the test says so.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class MenuParityFlowTest {
    @Test
    @DisplayName("NAV-02 Android and Desktop expose the same menu entries and the same project tree")
    fun both_platforms_expose_the_same_entries() {
        // The sheet's entries and the drawer's entries come from one model, and
        // that model covers the declared menu destinations exactly — no entry
        // on one platform that the other does not have, and none missing.
        assertEquals(
            DestinationKind.menuEntries.toSet(),
            AppMenu.allDestinations,
            "the menu offers something the declared menu destinations do not, or misses one",
        )

        // The Calendar is a tab and the Pomodoro is a menu entry: the two facts are
        // what the menu's shape is made of, and a reader of one without the other
        // cannot tell a destination that moved from one that was lost.
        assertTrue(
            AppDestination.Calendar !in AppMenu.allDestinations,
            "the Calendar is a tab, so it is not repeated in the menu: ${AppMenu.allDestinations}",
        )
        assertTrue(
            AppDestination.Pomodoro in AppMenu.allDestinations,
            "the Pomodoro is not an entry of the menu: ${AppMenu.allDestinations}",
        )
    }

    @Test
    @DisplayName("the drawer offers the entries of the shared menu model")
    fun the_drawer_offers_the_model() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            openDrawer()

            // Every entry of the model is on the screen. The drawer's column is
            // long, so the entries at the end are addressed by their tag rather
            // than by their position.
            for (entry in AppMenu.destinations) {
                awaitTag(entry.id)
            }
        }

    /**
     * The project roots are the other half of the menu's parity.
     *
     * The two surfaces draw the same section from the same state, and the one
     * switch between the two drawings is the expander: the Android sheet
     * passes `null` and draws the roots only, the Desktop drawer passes a
     * toggle and draws the tree (ADR
     * `2026-11-12-android-menu-roots-only.md`). The roots the two surfaces
     * offer are therefore the roots of one state — and the assertion is that
     * they are the same roots, in the same order.
     */
    @Test
    @DisplayName("the two drawings offer the same root projects in the same order")
    fun both_drawings_offer_the_same_root_projects() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val state =
                ProjectsTreeUiState.Loaded(
                    nodes =
                        persistentListOf(
                            node(project("Parent", "p-parent"), depth = 0, hasChildren = true),
                            node(project("Child", "p-child"), depth = 1),
                            node(project("Sibling", "p-sibling"), depth = 0),
                        ),
                    sectionExpanded = true,
                )
            setContent {
                Column {
                    // The Android sheet's drawing: no expander, so the roots.
                    Box(Modifier.testTag(ANDROID_DRAWING)) {
                        ProjectTreeSection(state = state, onToggleExpanded = null, onOpenProject = {})
                    }
                    // The Desktop drawer's drawing: the tree.
                    Box(Modifier.testTag(DESKTOP_DRAWING)) {
                        ProjectTreeSection(state = state, onToggleExpanded = {}, onOpenProject = {})
                    }
                }
            }

            val roots = listOf("p-parent", "p-sibling")
            assertEquals(
                roots.map { TestTags.menuProjectNode(it) },
                rootsIn(ANDROID_DRAWING, roots),
                "the sheet's drawing does not offer the root projects of the state, in their order",
            )
            assertEquals(
                roots.map { TestTags.menuProjectNode(it) },
                rootsIn(DESKTOP_DRAWING, roots),
                "the drawer's drawing does not offer the same root projects, in the same order",
            )
            // The sheet holds the roots and nothing below them: a child is
            // reached from its parent's own screen.
            assertTrue(
                onAllNodes(
                    hasAnyAncestor(hasTestTag(ANDROID_DRAWING)) and
                        hasTestTag(TestTags.menuProjectNode("p-child")),
                ).fetchSemanticsNodes().isEmpty(),
                "the sheet's drawing offers a child project, which belongs to its parent's own screen",
            )
        }

    /**
     * The rows of one drawing that stand for a root project, in the order they
     * are drawn: the section draws a flat list of rows, so the order of the
     * roots is the order of the rows.
     */
    private fun DesktopComposeUiTest.rootsIn(
        drawing: String,
        rootIds: List<String>,
    ): List<String> =
        rootIds
            .map { id ->
                val tag = TestTags.menuProjectNode(id)
                val rows =
                    onAllNodes(hasAnyAncestor(hasTestTag(drawing)) and hasTestTag(tag))
                        .fetchSemanticsNodes()
                assertEquals(1, rows.size, "the drawing '$drawing' does not hold exactly one row for '$id'")
                tag to rows.single().boundsInRoot.top
            }.sortedBy { (_, top) -> top }
            .map { (tag, _) -> tag }

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
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))

        /** The Android sheet's drawing of the project section. */
        const val ANDROID_DRAWING = "nav02_android_drawing"

        /** The Desktop drawer's drawing of the project section. */
        const val DESKTOP_DRAWING = "nav02_desktop_drawing"

        val NOW: Instant = Instant.parse("2026-11-23T12:00:00Z")
    }
}
