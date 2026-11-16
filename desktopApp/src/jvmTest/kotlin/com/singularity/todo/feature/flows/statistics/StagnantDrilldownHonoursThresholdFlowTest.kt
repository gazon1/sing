package com.singularity.todo.feature.flows.statistics

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.TasksRobot
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.goBack
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The stagnant card and the list it opens must describe the same tasks, under the
 * user's own staleness threshold.
 *
 * ## The defect this pins
 *
 * The card counted with the stored threshold; the agenda view it opened used the
 * 21-day default. The two agree on a fresh install — the default *is* 21 — which is
 * why the defect survived. They diverge the moment the user moves the slider.
 *
 * Both directions are covered, because one alone can be satisfied by a fixture that
 * shows nothing:
 *
 * - under a 60-day threshold a task idle 30 days is neither counted nor listed;
 * - under the 21-day default the *same kind of task* is both counted and listed.
 *
 * The second is the control: without it, an empty list would pass the first.
 *
 * ## Why one robot, not two
 *
 * `tasks(koin)` builds a new [TasksRobot], and the robot's seed counter starts at zero
 * on every instance — so two calls seed the same task id and the second overwrites the
 * first. That is not visible from the assertions: the surviving task is still rendered,
 * and a test written that way passes for the wrong reason. One robot, two seeds.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class StagnantDrilldownHonoursThresholdFlowTest {
    @Test
    fun the_stagnant_list_does_not_hold_what_a_wider_threshold_excludes() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            koin.get<SettingsRepository>().setStalenessThresholdDays(60)

            val robot = tasks(koin)
            // Idle 30 days: stagnant under the default 21, NOT under the configured 60.
            robot.givenIdle("Recent enough", days = 30)
            // Idle 90 days: stagnant under either threshold — the control.
            robot.givenIdle("Genuinely neglected", days = 90)
            assertEquals(
                2,
                koin
                    .get<TaskRepository>()
                    .observeAll()
                    .first()
                    .size,
                "both tasks were seeded",
            )

            openStagnantDrilldown()

            assertTagDisplayed(TestTags.taskItem("Genuinely neglected"))
            assertTagNotExists(TestTags.taskItem("Recent enough"))
        }

    @Test
    fun the_stagnant_list_holds_what_the_default_threshold_counts() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            // The default is 21 days and is not set here, so this is the control case.
            tasks(koin).givenIdle("Idle past the default", days = 30)

            openStagnantDrilldown()

            assertTagDisplayed(TestTags.taskItem("Idle past the default"))
        }

    /**
     * The screen is rebuilt from the stored value when it is opened again.
     *
     * `AgendaScreen` builds its ViewModel from the definition it is given, and Koin
     * keys that ViewModel by class inside the entry's store — so the second visit must
     * get a fresh store to see the new threshold. This pins that it does: the same
     * task is absent under 60 days, present under 21, with only a `goBack` between.
     */
    @Test
    fun reopening_the_drill_down_after_a_threshold_change_uses_the_new_value() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            val settings = koin.get<SettingsRepository>()
            settings.setStalenessThresholdDays(60)
            tasks(koin).givenIdle("Borderline", days = 30)

            openStagnantDrilldown()
            assertTagNotExists(TestTags.taskItem("Borderline"))

            goBack()
            settings.setStalenessThresholdDays(21)
            openStagnantDrilldown()

            assertTagDisplayed(TestTags.taskItem("Borderline"))
        }

    /** A task nobody has touched for [days], undated, so only stagnation can select it. */
    private suspend fun TasksRobot.givenIdle(
        title: String,
        days: Long,
    ) = givenUndatedAt(title = title, updatedAt = NOW - days.days)

    private fun DesktopComposeUiTest.openStagnantDrilldown() {
        tapTab("Statistics")
        clickTag(TestTags.STATISTICS_TAB_HEALTH)
        clickTag(TestTags.HEALTH_BUCKET_STAGNANT)

        // The drill-down replaced the statistics screen: the tab is gone once the push
        // lands, so this is the arrival signal for the agenda, not a proxy for it.
        assertTagNotExists(TestTags.STATISTICS_TAB_HEALTH)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(NOW)
    }
}
