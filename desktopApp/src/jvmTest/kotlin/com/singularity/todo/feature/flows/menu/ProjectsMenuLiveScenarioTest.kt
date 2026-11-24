package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performScrollTo
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the menu scenario `MENU-03` — the project tree reflects creations and renames made elsewhere.
 *
 * The drawer is the desktop's menu, and it draws the same tree the Android
 * sheet draws: one composable, two drawings. The drawer shares a window with
 * the app, so liveness is observed while the menu stays open; the Android sheet
 * closes on outside changes and must be reopened, which remains a device-only
 * residue in the Maestro flow.
 *
 * The projects are seeded through the repository rather than through the UI,
 * because a tree is about the arrangement of projects the user already has,
 * and building that arrangement through the editor would be a slower way of
 * saying the same thing.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ProjectsMenuLiveScenarioTest {
    /**
     * The owner of `MENU-03`.
     *
     * The scenario is one claim with two halves — the menu's project list shows
     * a project created elsewhere *and* the new name of a project renamed
     * elsewhere — and the traceability rule allows **one** owner per scenario
     * and target. So the owner carries both halves: a test that owns the
     * scenario and asserts only the creation is a claim the rename half of the
     * spec makes and nobody proves.
     *
     * Both changes are made while the drawer is open, so the liveness is
     * observed rather than inferred from a reopen.
     */
    @Test
    @DisplayName("MENU-03 the project tree reflects creations and renames made elsewhere")
    fun the_tree_reflects_creations_and_renames() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            seedProject(koin, "roadmap", "Roadmap")

            openDrawer()
            expandProjectsSection()
            awaitTag(TestTags.menuProjectNode("roadmap"))

            // Renamed while the menu is open: the same row keeps its id and shows
            // the new name without the menu being closed and reopened in between.
            seedProject(koin, "roadmap", "Renamed")

            awaitTag(TestTags.menuProjectNode("roadmap")).performScrollTo()
            assertTextDisplayed("Renamed")

            // Created while the menu is open: the tree has to show it without the
            // menu being closed and reopened in between.
            seedProject(koin, "robot-late", "Latecomer")

            awaitTag(TestTags.menuProjectNode("robot-late"))
        }
}
