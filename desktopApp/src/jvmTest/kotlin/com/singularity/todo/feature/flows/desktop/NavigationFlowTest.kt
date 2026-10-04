package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.assertCurrentTab
import com.singularity.todo.test.helpers.awaitTag
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
    fun drawer_exposes_every_destination() = runDesktopAppTest(checkA11y = true) {
        openDrawer()

        DesktopShell.TABS.forEach { label ->
            onNodeWithContentDescription(label).assertIsDisplayed()
        }
        DesktopShell.MENU_ENTRIES.forEach { label ->
            onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun pomodoro_and_calendar_render_their_own_screens() = runDesktopAppTest(checkA11y = true) {
        // The phase label is tagged, so this proves the Pomodoro VM produced state
        // rather than merely that the drawer entry was clicked.
        tapTab("Pomodoro")
        onNodeWithTag(TestTags.Pomodoro.PHASE_LABEL).assertIsDisplayed()

        // The month grid's weekday columns exist only in the calendar.
        tapTab("Calendar")
        onNodeWithText("Mon").assertIsDisplayed()
        onNodeWithText("Sun").assertIsDisplayed()
    }

    @Test
    fun inbox_is_reachable_from_the_default_today_tab() = runDesktopAppTest(checkA11y = true) {
        // Both agendas are empty on a fresh database and the title text is
        // ambiguous, so the drawer's own Selected semantics is what proves the
        // tab actually switched.
        assertCurrentTab("Today")

        tapTab("Inbox")

        assertCurrentTab("Inbox")
    }

    @Test
    fun plans_detail_survives_tab_roundtrip() = runDesktopAppTest(checkA11y = true) { koin ->
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
    fun project_detail_task_opens_from_plans_instead_of_falling_back() = runDesktopAppTest(checkA11y = true) { koin ->
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
        tasks(koin).given(due = todayInSystemZone(), title = "Ship the policy", projectId = project.id)

        tapTab("Plans")
        awaitTag(TestTags.projectCard("Roadmap")).performClick()
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()

        // The open that used to be swallowed → task detail must appear.
        awaitTag(TestTags.taskItem("Ship the policy")).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT).assertIsDisplayed()
        onNodeWithText("Ship the policy").assertIsDisplayed()

        // Back returns to the project — the origin stayed underneath (REQ-NAV-001).
        onNodeWithContentDescription("Back").performClick()
        awaitTag(TestTags.PROJECT_DETAIL_QUICK_ADD).assertIsDisplayed()
    }
}
