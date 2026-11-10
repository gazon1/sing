package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.CLOCK
import com.singularity.todo.feature.flows.menu.ProjectsMenuScenarioSupport.seedProjects
import com.singularity.todo.test.helpers.assertContentDescriptionReachable
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Desktop automation for the menu scenario `MENU-07` — the Projects section starts folded.
 *
 * A profile with a hundred projects draws a tree longer than the screen, and
 * the tree sits below the entries the user opened the menu for. So the section
 * is folded until the user unfolds it.
 *
 * The hundred projects are seeded through the repository rather than through
 * the UI: a tree that long cannot be staged by tapping, and the point of the
 * scenario is the *number* of projects, not the way they were made.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ProjectsMenuCollapsedScenarioTest {
    @Test
    @DisplayName("MENU-07 the Projects section of the menu starts folded, so the destinations stay reachable")
    fun the_projects_section_starts_folded() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            seedProjects(koin, 120)

            openDrawer()

            // The tree is folded away, so there is no project row for a scroll
            // to pass through.
            assertTagNotExists(TestTags.menuProjectNode("bulk-001"))

            // The way into the full list is there, folded or not.
            scrollToTag(TestTags.MENU_ALL_PROJECTS)
            assertTagDisplayed(TestTags.MENU_ALL_PROJECTS)

            // Unfolding draws the tree, and the toggle says what it does — that
            // is what a screen reader announces.
            expandProjectsSection()
            scrollToTag(TestTags.menuProjectNode("bulk-001"))
            assertTagDisplayed(TestTags.menuProjectNode("bulk-001"))
            assertContentDescriptionReachable("Collapse projects")

            // Folding it again takes the tree off the screen.
            foldProjectsSection()
            awaitTagGone(TestTags.menuProjectNode("bulk-001"))
        }
}
