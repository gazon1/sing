package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProject
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the menu scenario `MENU-06` — the full project list stays reachable from the menu.
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
class ProjectsMenuAllProjectsScenarioTest {

    @Test
    @DisplayName("MENU-06 the full project list stays reachable from the menu")
    fun the_full_project_list_stays_reachable() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        seedProject(koin, "robot-root", "Product")

        openDrawer()
        clickTagScrolled("menu_all_projects")

        // The list is the screen that holds the projects — the tree is only a
        // way into them.
        awaitTag(TestTags.projectCard("Product"))
    }
}
