package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the menu scenario `MENU-02` — tapping a project in the tree opens the project detail screen.
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
class ProjectsMenuOpenScenarioTest {

    @Test
    @DisplayName("MENU-02 tapping a project in the tree opens the project detail screen")
    fun tapping_a_project_opens_it() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        seedProject(koin, "robot-root", "Product")

        openDrawer()
        awaitTag(TestTags.menuProjectNode("robot-root")).performScrollTo().performClick()

        // The quick-add field only exists in the detail screen's content state.
        // Waited for, not asserted: the tree's tap asks the projects graph to
        // open the project, and the request travels through the graph's own
        // composition — more than one frame.
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD)
    }
}
