package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The «Выполнено …» line of a completed task, on the real screen.
 *
 * The date comes from the task's own `completedAt` and is rendered in the app's zone
 * port. Two things can each be wrong on their own here:
 *
 * * the wrong field — today's date instead of the completion's;
 * * the wrong zone — a task finished late in the evening is the *next* day in a zone
 *   ahead of UTC, and "12 Nov" where the answer is "13 Nov" is the bug.
 *
 * The clock is pinned late in the evening UTC so the zone really moves the day: an
 * assertion at midday would pass for any zone within ±11 hours and prove nothing.
 *
 * The locale half is covered where it can be: the label reads the same
 * `completed_on` resource and the same month names as the rest of the editor
 * (`localizedShortDateWithYear`), and `runIsolatedComposeTest` pins the JVM locale for
 * that. This harness mounts in English on purpose, so asserting Russian here would
 * measure the harness.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailCompletedDateTest {
    @Test
    fun `the completion date is the task's own, in the app's zone`() =
        runDesktopAppTest(
            clock = FakeClock(COMPLETED_AT),
            overrides = module { single<TimeZoneProvider> { AheadOfUtc } },
        ) { koin ->
            tasks(koin).given(
                // Today in the app's own zone, not the host's: at 21:30Z the agenda's
                // "today" in Moscow is already the 13th, and a task due on the 12th
                // would sit in the overdue bucket instead of the list this opens from.
                due = todayAt(FakeClock(COMPLETED_AT), TimeZone.of("Europe/Moscow")),
                title = "Finished work",
                completed = true,
                completedAt = COMPLETED_AT,
            )

            tapTab("Today")
            awaitTag(TestTags.taskItem("Finished work")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            assertTagDisplayed(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            // 21:30Z is the 13th in UTC+1. A screen reading the host's zone would say
            // the 12th and this fails.
            assertTagTextEquals(TestTags.TASK_DETAIL_COMPLETED_LABEL, "Done 13 Nov 2026")
        }

    @Test
    fun `an open task has no completion date`() =
        runDesktopAppTest(
            clock = FakeClock(COMPLETED_AT),
            overrides = module { single<TimeZoneProvider> { AheadOfUtc } },
        ) { koin ->
            tasks(koin).given(due = LocalDate(2026, 11, 12), title = "Open work")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Open work")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            assertTagNotExists(TestTags.TASK_DETAIL_COMPLETED_LABEL)
        }

    /**
     * Moscow — a steady UTC+3, so 21:30Z is 00:30 the next day. The direction matters:
     * a zone ahead of UTC turns a late-evening completion into the *next* day, which is
     * the case a naive implementation gets wrong.
     */
    private object AheadOfUtc : TimeZoneProvider {
        override fun current(): TimeZone = TimeZone.of("Europe/Moscow")
    }

    private companion object {
        /** 21:30 UTC — past 21:00, so a UTC+3 zone has already crossed midnight. */
        val COMPLETED_AT: Instant = Instant.parse("2026-11-12T21:30:00Z")
    }
}
