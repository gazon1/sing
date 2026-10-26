package com.singularity.todo.feature.flows.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTagScrolled
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.scrollToTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Desktop automation for the menu scenarios — `MENU-01` … `MENU-06`.
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
class ProjectsMenuFlowTest {

    @Test
    @DisplayName("MENU-01 the menu shows projects as a tree with children indented")
    fun the_menu_shows_a_tree() = runDesktopAppTest(clock = CLOCK) { koin ->
        seedProject(koin, "robot-root", "Product")
        seedProject(koin, "robot-child", "Website", parent = "robot-root")

        openDrawer()

        val parent = awaitTag(TestTags.menuProjectNode("robot-root"))
        val child = awaitTag(TestTags.menuProjectNode("robot-child"))
        parent.performScrollTo()
        child.performScrollTo()

        // The indentation is the assertion: a child sits under its parent, and
        // a flat list of names would lose the one thing a tree shows.
        val parentLeft = parent.getUnclippedBoundsInRoot().left
        val childLeft = child.getUnclippedBoundsInRoot().left
        assertTrue(
            childLeft > parentLeft,
            "the child is not indented under its parent: $childLeft is not to the right of $parentLeft",
        )
    }

    @Test
    @DisplayName("MENU-02 tapping a project in the tree opens the project detail screen")
    fun tapping_a_project_opens_it() = runDesktopAppTest(clock = CLOCK) { koin ->
        seedProject(koin, "robot-root", "Product")

        openDrawer()
        awaitTag(TestTags.menuProjectNode("robot-root")).performScrollTo().performClick()

        // The quick-add field only exists in the detail screen's content state.
        // Waited for, not asserted: the tree's tap asks the projects graph to
        // open the project, and the request travels through the graph's own
        // composition — more than one frame.
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD)
    }

    @Test
    @DisplayName("MENU-03 the project tree reflects creations made elsewhere")
    fun the_tree_is_live() = runDesktopAppTest(clock = CLOCK) { koin ->
        openDrawer()

        // Created while the menu is open: the tree has to show it without the
        // menu being closed and reopened in between.
        seedProject(koin, "robot-late", "Latecomer")

        awaitTag(TestTags.menuProjectNode("robot-late"))
    }

    @Test
    @DisplayName("MENU-04 the Calendar is an entry of the menu")
    fun the_calendar_is_an_entry_of_the_menu() = runDesktopAppTest(clock = CLOCK) {
        openDrawer()

        assertTextDisplayed("Calendar")
    }

    @Test
    @DisplayName("MENU-05 a project without children shows no expander and a closed project is absent")
    fun a_childless_project_has_no_expander() = runDesktopAppTest(clock = CLOCK) { koin ->
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

    @Test
    @DisplayName("MENU-06 the full project list stays reachable from the menu")
    fun the_full_project_list_stays_reachable() = runDesktopAppTest(clock = CLOCK) { koin ->
        seedProject(koin, "robot-root", "Product")

        openDrawer()
        clickTagScrolled("menu_all_projects")

        // The list is the screen that holds the projects — the tree is only a
        // way into them.
        awaitTag(TestTags.projectCard("Product"))
    }

    private suspend fun seedProject(
        koin: org.koin.core.Koin,
        id: String,
        name: String,
        parent: String? = null,
        closed: Boolean = false,
    ) {
        val epoch = Instant.fromEpochMilliseconds(0)
        koin.get<ProjectsRepository>().upsert(
            ProjectDocument(
                Project(
                    id = ProjectId.fromString(id),
                    name = name,
                    color = 0xFF2196F3.toInt(),
                    createdAt = epoch,
                    updatedAt = epoch,
                    parentId = parent?.let { ProjectId.fromString(it) },
                    closedAt = if (closed) epoch else null,
                    userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
                ),
                emptySet(),
            ),
        )
    }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
    }
}
