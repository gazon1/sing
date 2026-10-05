package com.singularity.todo.feature.flows.desktop

import kotlinx.datetime.LocalDate
import com.singularity.todo.test.fakes.FakeClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.openDrawer
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Desktop mirror of `Maestro/flows/nav/bottom-nav-tabs.yaml`.
 *
 * The Android flow walks the bottom bar; desktop reaches the same six
 * destinations through the `ModalNavigationDrawer` hamburger instead.
 *
 * Every arrival is asserted on content the destination itself renders — never on
 * the drawer entry, which slides off-screen as soon as it is tapped. A tap that
 * silently no-ops therefore fails instead of passing on a leftover node.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class NavigationFlowTest {
    @Test
    fun drawer_exposes_every_destination() = runDesktopAppTest(clock = CLOCK, checkA11y = true) {
        openDrawer()

        DesktopShell.TABS.forEach { label ->
            assertContentDescriptionDisplayed(label)
        }
        DesktopShell.MENU_ENTRIES.forEach { label ->
            assertContentDescriptionDisplayed(label)
        }
    }

    @Test
    fun pomodoro_and_calendar_render_their_own_screens() = runDesktopAppTest(clock = CLOCK, checkA11y = true) {
        // The phase label is tagged, so this proves the Pomodoro VM produced state
        // rather than merely that the drawer entry was clicked.
        tapTab("Pomodoro")
        assertTagDisplayed(TestTags.Pomodoro.PHASE_LABEL)

        // The month grid's weekday columns exist only in the calendar.
        tapTab("Calendar")
        assertTextDisplayed("Mon")
        assertTextDisplayed("Sun")
    }

    @Test
    fun inbox_is_reachable_from_the_default_today_tab() = runDesktopAppTest(clock = CLOCK, checkA11y = true) {
        // Both agendas are empty on a fresh database and the title text is
        // ambiguous, so the drawer's own Selected semantics is what proves the
        // tab actually switched.
        assertCurrentTab("Today")

        tapTab("Inbox")

        assertCurrentTab("Inbox")
    }

    @Test
    fun plans_detail_survives_tab_roundtrip() = runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
        val epoch = Instant.fromEpochMilliseconds(0)
        koin.get<ProjectsRepository>().upsert(
            Project(
                id = ProjectId.fromString("robot-project-roundtrip"),
                name = "Roadmap",
                color = 0xFF2196F3.toInt(),
                createdAt = epoch,
                updatedAt = epoch,
                userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
            ),
        )

        tapTab("Plans")
        awaitTag(TestTags.projectCard("Roadmap")).performClick()
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()

        tapTab("Today")
        tapTab("Plans")

        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()
    }

    /**
     * REQ-NAV-002 — a cross-feature open must not degrade into back-navigation.
     *
     * Before the open policy, the Plans entry's `onExitGraph` allow-list was
     * `{ nav.goBack() }`, so `ProjectsNavigator.openTask` from the project detail
     * opened underneath Plans was silently swallowed: the app fell back to the
     * project list instead of opening the task. The uniform policy callback makes
     * the same request resolve to `ExitAndOpen` and push the tasks graph on top of
     * the Plans stack — the project staying underneath for Back.
     */
    @Test
    fun project_detail_task_opens_from_plans_instead_of_falling_back() = runDesktopAppTest(
        clock = CLOCK,
        checkA11y = true,
    ) { koin ->
        val epoch = Instant.fromEpochMilliseconds(0)
        val project = koin.get<ProjectsRepository>().upsert(
            Project(
                id = ProjectId.fromString("robot-project-policy"),
                name = "Roadmap",
                color = 0xFF2196F3.toInt(),
                createdAt = epoch,
                updatedAt = epoch,
                userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
            ),
        )
        tasks(koin).given(due = today, title = "Ship the policy", projectId = project.id)

        tapTab("Plans")
        awaitTag(TestTags.projectCard("Roadmap")).performClick()
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()

        // The open that used to be swallowed → task detail must appear.
        awaitTag(TestTags.taskItem("Ship the policy")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).assertIsDisplayed()
        assertTextDisplayed("Ship the policy")

        // Back returns to the project — the origin stayed underneath (REQ-NAV-001).
        clickContentDescription("Back")
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()
    }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)
    }
}
