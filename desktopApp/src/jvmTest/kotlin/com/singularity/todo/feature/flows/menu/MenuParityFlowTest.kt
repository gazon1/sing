package com.singularity.todo.feature.flows.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
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
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
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
    /**
     * The owner of `NAV-02`.
     *
     * The scenario is one claim — both platforms expose the same menu entries
     * and the same project tree — and the traceability rule allows **one**
     * owner per scenario and target, so the owner has to carry every
     * assertion the scenario is made of. A second test that asserts part of
     * it is a second, unowned claim: the matrix would show a carrier that
     * does not prove the whole scenario. So the parts live in helpers, and
     * this test runs all of them.
     */
    @Test
    @DisplayName("NAV-02 Android and Desktop expose the same menu entries and the same project tree")
    fun both_platforms_expose_the_same_entries() {
        assertTheMenuModelIsTheDeclaredMenu()
        assertTheDrawerOffersEveryEntryOfTheModel()
        assertBothDrawingsOfferTheSameRootProjects()
        assertTheRealDrawerOffersTheWholeTree()
    }

    /**
     * The sheet's entries and the drawer's entries come from one model, and
     * that model covers the declared menu destinations exactly — no entry on
     * one platform that the other does not have, and none missing.
     *
     * The Calendar is a tab and the Pomodoro is a menu entry: the two facts
     * are what the menu's shape is made of, and a reader of one without the
     * other cannot tell a destination that moved from one that was lost.
     */
    private fun assertTheMenuModelIsTheDeclaredMenu() {
        assertEquals(
            DestinationKind.menuEntries.toSet(),
            AppMenu.allDestinations,
            "the menu offers something the declared menu destinations do not, or misses one",
        )

        assertTrue(
            AppDestination.Calendar !in AppMenu.allDestinations,
            "the Calendar is a tab, so it is not repeated in the menu: ${AppMenu.allDestinations}",
        )
        assertTrue(
            AppDestination.Pomodoro in AppMenu.allDestinations,
            "the Pomodoro is not an entry of the menu: ${AppMenu.allDestinations}",
        )
    }

    /**
     * Every entry of the model is on the screen of the drawer.
     *
     * The drawer's column is long, so the entries at the end are addressed by
     * their tag rather than by their position.
     */
    private fun assertTheDrawerOffersEveryEntryOfTheModel() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            openDrawer()

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
     *
     * The assertion is on the whole set of rows of each drawing, not on the
     * rows the test happens to expect: a drawing that offered a project
     * nobody asked about would pass an assertion that only looks for the
     * expected ones. The two drawings differ in exactly one row — the child,
     * which the sheet keeps to its parent's screen and the drawer unfolds.
     */
    private fun assertBothDrawingsOfferTheSameRootProjects() =
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

            // The sheet holds the roots of the state and nothing else: a child
            // is reached from its parent's own screen.
            assertEquals(
                listOf("p-parent", "p-sibling").map { TestTags.menuProjectNode(it) },
                projectRowsIn(ANDROID_DRAWING),
                "the sheet's drawing does not offer the root projects of the state, and nothing else, in their order",
            )
            // The drawer offers the same roots, with the child unfolded between
            // them — the tree, in the order the state lists it.
            assertEquals(
                listOf("p-parent", "p-child", "p-sibling").map { TestTags.menuProjectNode(it) },
                projectRowsIn(DESKTOP_DRAWING),
                "the drawer's drawing does not offer the tree of the state, in their order",
            )
        }

    /**
     * The tree the **real** Desktop drawer offers.
     *
     * The hand-drawn check above draws the two drawings by hand, which proves what the
     * composable does with a given state — not what the drawer does. A drawer
     * that passed `null` where a toggle belongs would draw the roots only, and
     * the hand-drawn pair would still agree with each other: both would show
     * the same rows, because both were written by the same hand.
     *
     * So this one opens the actual drawer, seeds a tree through the repository
     * and reads the rows the drawer actually put on the screen. The whole set
     * is compared, not the rows the test happened to expect: a drawer that
     * offered a project nobody asked about has to fail here.
     *
     * The rows are compared in the order they are drawn, which is the order of
     * the tree: a parent, then its child, then the next root.
     */
    private fun assertTheRealDrawerOffersTheWholeTree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            seedProject(koin, "nav02-parent", "Parent")
            seedProject(koin, "nav02-child", "Child", parent = "nav02-parent")
            seedProject(koin, "nav02-sibling", "Sibling")

            openDrawer()
            expandProjectsSection()

            awaitTag(TestTags.menuProjectNode("nav02-parent"))
            awaitTag(TestTags.menuProjectNode("nav02-child"))
            awaitTag(TestTags.menuProjectNode("nav02-sibling"))

            assertEquals(
                listOf("nav02-parent", "nav02-child", "nav02-sibling").map { TestTags.menuProjectNode(it) },
                projectRowsInTheDrawer(),
                "the drawer does not offer the tree of the state, in the order it draws it",
            )
        }

    /**
     * The project rows the drawer shows, in the order it draws them.
     *
     * The rows are read out of the whole tree by their tag prefix rather than
     * by the ids the test expects, so that a row nobody expected is a row the
     * assertion sees.
     *
     * A row that is drawn twice is a bug, not a duplicate to fold away: the
     * two rows are separate nodes with the same tag, and a deduplicating read
     * would hide the second one. So the duplicates are counted and the test
     * says which row they are.
     *
     * The order is the order the rows are drawn in, taken from where they sit
     * on the screen: the drawer draws them in one column, so the row above is
     * the row that comes first. The bounds are the *unclipped* ones, because a
     * row below the fold has a position even when the fold hides it.
     */
    private fun DesktopComposeUiTest.projectRowsInTheDrawer(): List<String> {
        val tags =
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
                .fetchSemanticsNodes()
                .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) }
                .filter { tag -> tag.startsWith(PROJECT_ROW_TAG) }
        assertEquals(
            tags.distinct().size,
            tags.size,
            "the drawer draws a project row more than once: $tags",
        )
        return tags.sortedBy { tag -> unclippedBoundsOfTag(tag).top }
    }

    /**
     * The project rows one drawing shows, in the order it draws them: the
     * section draws a flat list of rows, so the order of the rows is the order
     * of the tree.
     *
     * The rows are found by their tag rather than by the ids the test expects,
     * so that a row nobody expected is a row the assertion sees.
     */
    private fun DesktopComposeUiTest.projectRowsIn(drawing: String): List<String> =
        onAllNodes(hasAnyAncestor(hasTestTag(drawing)))
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.let { node to it } }
            .filter { (_, tag) -> tag.startsWith(PROJECT_ROW_TAG) }
            .sortedBy { (node, _) -> node.boundsInRoot.top }
            .map { (_, tag) -> tag }

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

        /** The prefix every project row of the menu carries. */
        const val PROJECT_ROW_TAG = "menu_project_node_"

        val NOW: Instant = Instant.parse("2026-11-23T12:00:00Z")
    }
}
