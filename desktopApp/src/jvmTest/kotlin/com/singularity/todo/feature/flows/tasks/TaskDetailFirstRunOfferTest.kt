package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The task detail offers its quick actions where a person can reach them.
 *
 * The offer is the first-run banner, and it has a place of its own: above the attribute
 * rows, for every task, whatever else the task carries. It shows on a task that is new
 * and empty, which is what the seeded task is — a task created minutes ago with no
 * description, no checklist and no finished subtask.
 *
 * The pending AI proposals share that place, and a task with no pending proposals shows
 * no proposals stack at all.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailFirstRunOfferTest {
    @Test
    @DisplayName("a new empty task offers its quick actions above the attribute rows")
    fun a_new_empty_task_offers_its_quick_actions_above_the_attribute_rows() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            tasks(koin).given(
                due = todayAt(TEST_CLOCK, TEST_ZONE),
                title = "Fresh task",
                // The offer is drawn for a task that is minutes old, and the clock
                // is fixed — so the seeded task has to be as young as that clock.
                createdAt = TEST_CLOCK.now(),
            )

            tapTab("Today")
            awaitTag(TestTags.taskItem("Fresh task")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // The offer is on the screen of the task detail.
            assertTagDisplayed(TestTags.FIRST_RUN_SECTION)

            // And above the attribute rows. The priority row is always drawn and it is
            // the first of them, so it is the anchor the order is measured against.
            val offer = unclippedBoundsOfTag(TestTags.FIRST_RUN_SECTION)
            val priority = unclippedBoundsOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTrue(
                offer.bottom <= priority.top,
                "the offer is drawn below the attribute rows: offer $offer, priority $priority",
            )

            // Nothing is waiting for a decision, so there is no proposals stack.
            assertTagNotExists(TestTags.TASK_DETAIL_PROPOSALS_SECTION)
        }

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test can roll the
         * date across a midnight boundary and empty the Today tab.
         *
         * The clock is fixed, and the seeded task is created at that clock's moment — so
         * the task is always minutes old, which is what keeps the first-run offer on the
         * screen instead of ageing out of the test.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
    }
}
