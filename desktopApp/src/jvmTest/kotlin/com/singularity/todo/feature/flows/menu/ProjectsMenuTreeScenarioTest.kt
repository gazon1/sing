package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performScrollTo
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop automation for the menu scenario `MENU-01` — the menu shows projects as a tree with children indented.
 *
 * The drawer is the desktop's menu, and it draws the same tree the Android
 * sheet draws: one composable, two drawings.
 *
 * The projects are seeded through the repository rather than through the UI,
 * because a tree is about the arrangement of projects the user already has,
 * and building that arrangement through the editor would be a slower way of
 * saying the same thing.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ProjectsMenuTreeScenarioTest {

    @Test
    @DisplayName("MENU-01 the menu shows projects as a tree with children indented")
    fun the_menu_shows_a_tree() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        seedProject(koin, "robot-root", "Product")
        seedProject(koin, "robot-child", "Website", parent = "robot-root")

        openDrawer()

        val parent = awaitTag(TestTags.menuProjectNode("robot-root"))
        val child = awaitTag(TestTags.menuProjectNode("robot-child"))
        parent.performScrollTo()
        child.performScrollTo()

        // The indentation is the assertion: a child sits under its parent, and
        // a flat list of names would lose the one thing a tree shows.
        val parentLeft = unclippedBoundsOfTag(TestTags.menuProjectNode("robot-root")).left
        val childLeft = unclippedBoundsOfTag(TestTags.menuProjectNode("robot-child")).left
        assertTrue(
            childLeft > parentLeft,
            "the child is not indented under its parent: $childLeft is not to the right of $parentLeft",
        )
    }
}
