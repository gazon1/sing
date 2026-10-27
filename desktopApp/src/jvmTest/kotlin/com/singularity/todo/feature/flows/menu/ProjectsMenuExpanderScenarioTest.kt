package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop automation for the menu scenario `MENU-05` — a project without children shows no expander and a closed
 * project is absent.
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
class ProjectsMenuExpanderScenarioTest {

    @Test
    @DisplayName("MENU-05 a project without children shows no expander and a closed project is absent")
    fun a_childless_project_has_no_expander() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        seedProject(koin, "robot-childless", "Childless")
        seedProject(koin, "robot-closed", "Closed", closed = true)

        openDrawer()

        // The tree sits at the end of the drawer, below the fold.
        scrollToTag(TestTags.menuProjectNode("robot-childless"))
        assertTagDisplayed(TestTags.menuProjectNode("robot-childless"))
        // Nothing to expand, so no expander is offered.
        assertTrue(
            countNodes(TestTags.menuProjectExpander("robot-childless")) == 0,
            "a project without children must not offer an expander",
        )
        // A closed project has left the work, and the tree shows the work.
        awaitTagGone(TestTags.menuProjectNode("robot-closed"))
    }
}
