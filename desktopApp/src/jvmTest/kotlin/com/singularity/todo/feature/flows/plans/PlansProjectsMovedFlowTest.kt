package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectDocument
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop automation for `PLANS-06` — the Plans tab stopped being the project
 * list, and the projects went to the menu.
 *
 * Both halves are asserted, and that is the point of the scenario: a screen
 * that simply stopped listing projects would pass a check on the tab alone and
 * lose a feature. The tab must show the timeline, and the projects must be
 * found in the menu — as a tree.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class PlansProjectsMovedFlowTest {

    @Test
    @DisplayName("PLANS-06 the Plans tab no longer shows a project list")
    fun the_tab_shows_tasks_and_the_menu_shows_the_projects() = runDesktopAppTest(
        clock = CLOCK,
        checkA11y = true,
    ) { koin ->
        seedProject(koin, "robot-root", "Product")
        tasks(koin).given(due = TODAY, title = "Due today")

        // The tab is the timeline: a task, in a section that names its day.
        tapTab("Plans")
        assertTagDisplayed(TestTags.plansSection("day:2026-09-16"))
        assertTagDisplayed(TestTags.taskItem("Due today"))

        // And no project list: the project is not on the tab any more.
        awaitTagGone(TestTags.projectCard("Product"))

        // The project is not lost — it is in the menu, as a tree.
        openDrawer()
        awaitTag(TestTags.menuProjectNode("robot-root"))
    }

    private suspend fun seedProject(
        koin: org.koin.core.Koin,
        id: String,
        name: String,
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
                    userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
                ),
                emptySet(),
            ),
        )
    }

    private companion object {
        val CLOCK: FakeClock = FakeClock(Instant.parse("2026-09-16T10:00:00Z"))
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
    }
}
