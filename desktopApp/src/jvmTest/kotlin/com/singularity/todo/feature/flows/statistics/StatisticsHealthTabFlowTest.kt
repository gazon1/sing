package com.singularity.todo.feature.flows.statistics

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The statistics screen opens on the Tasks tab; the health buckets are on the
 * Health tab.
 *
 * The three health drill-down flows wait for `statistics_tab_health` and fail
 * with "element not found" when it is not there. The id is not a rename and the
 * tab is not gone: the tab row carries a stable id per tab precisely so a flow
 * can select the Health tab before waiting for a bucket. This test pins that
 * contract, so a future rename breaks a JVM test instead of three Maestro flows
 * at once.
 *
 * ## Coverage
 *
 * - the statistics screen shows all three tabs, each with its own id
 * - selecting the Health tab puts the bucket cards on screen
 * - the attention items are on that tab, one row per item, with a stable id
 * - setting an item aside takes the row off the screen
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class StatisticsHealthTabFlowTest {
    @Test
    fun the_health_tab_is_reachable_and_carries_its_own_id() =
        runDesktopAppTest(
            clock = CLOCK,
            checkA11y = true,
        ) {
            tapTab("Statistics")

            assertTagDisplayed(TestTags.STATISTICS_TAB_TASKS)
            assertTagDisplayed(TestTags.STATISTICS_TAB_TIME)
            assertTagDisplayed(TestTags.STATISTICS_TAB_HEALTH)

            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            assertTagDisplayed(TestTags.HEALTH_BUCKET_BLOCKED)
            assertTagDisplayed(TestTags.HEALTH_BUCKET_OVERDUE)
            assertTagDisplayed(TestTags.HEALTH_BUCKET_STAGNANT)
        }

    /**
     * The attention items reach the Health tab, and setting one aside takes it
     * off the screen.
     *
     * The row carries the item's own key, so a test can name the row it means
     * rather than counting rows — and the dismissal is asserted on the same
     * row, which is the only thing that says the dismissal removed *that* item
     * and not another one.
     */
    @Test
    fun the_attention_items_are_on_the_health_tab_and_can_be_set_aside() =
        runDesktopAppTest(
            clock = CLOCK,
            checkA11y = true,
        ) { koin ->
            seedOverdueTask(koin)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            assertTagDisplayed(TestTags.attentionNudge(NUDGE_KEY))

            clickTag(TestTags.attentionNudgeDismiss(NUDGE_KEY))

            awaitTagGone(TestTags.attentionNudge(NUDGE_KEY))
        }

    /** A task whose due date has passed — the first thing the list names. */
    private suspend fun seedOverdueTask(koin: Koin) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val task =
            testTask(
                id = TaskId(TASK_ID),
                title = "Late task",
                dueDate = today.minus(1, DateTimeUnit.DAY),
                userId = userId,
            )
        koin.get<TaskRepository>().upsert(TaskDocument.of(task))
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)

        /**
         * The day the Statistics screen sees.
         *
         * The screen reads the day in the system's own time zone, so the test
         * reads it the same way — a hard-coded day would disagree with the
         * screen on every machine that is not in UTC.
         */
        val today: LocalDate = FIXED_NOW.toLocalDateTime(TimeZone.currentSystemDefault()).date

        const val TASK_ID = "nudge-overdue"
        const val NUDGE_KEY = "task:$TASK_ID"
    }
}
