package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The Upcoming tab says where a task dated beyond next week is.
 *
 * The three agenda tabs partition the tasks — Inbox, Today, Upcoming — and a task
 * dated further out than next week belongs to none of them: it is in the Calendar and
 * in the Plans timeline. The decision that puts it there is in
 * `docs/decisions/2026-10-31-the-agenda-tabs-are-disjoint.md`, and until now
 * nothing on the screen said so. A person who reached the end of the tab and did not
 * see their task had no way to learn that it was not lost.
 *
 * Two cases, because the pointer has two homes: the last line of the list when the
 * tab has tasks, and the line under the empty state when it has none. Both are
 * asserted here, through the sentence itself.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaBeyondNextWeekNoteFlowTest {
    @Test
    @DisplayName("AGENDA-POINTER-01 the Upcoming tab points at the Calendar and the Plans timeline")
    fun the_upcoming_tab_points_at_the_calendar_and_the_plans_timeline() =
        runDesktopAppTest(checkA11y = true, clock = CLOCK) { koin ->
            tasks(koin).given(due = insideTheUpcomingTab(), title = "Later task")

            tapTab("Upcoming")
            // The tab really has content: the pointer is the last line of this list,
            // not the empty state under it.
            assertTagDisplayed(TestTags.taskItem("Later task"))

            assertTextDisplayed(POINTER)
        }

    @Test
    @DisplayName("AGENDA-POINTER-02 an empty Upcoming tab still points at the Calendar and the Plans timeline")
    fun an_empty_upcoming_tab_still_points_at_the_calendar_and_the_plans_timeline() =
        runDesktopAppTest(checkA11y = true, clock = CLOCK) {
            tapTab("Upcoming")

            assertTextDisplayed(POINTER)
        }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)

        /**
         * A date the Upcoming tab is bound to show.
         *
         * Two days out, in the zone the agenda resolves its dates in. Two days rather
         * than one, because "tomorrow" is the one date a zone disagreement of a single
         * day would move into the Today tab; two days out is inside the tab's own
         * range under either reading, so the row is there and the pointer is the last
         * line of the list.
         */
        fun insideTheUpcomingTab() =
            FIXED_NOW
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
                .plus(2, DateTimeUnit.DAY)

        /**
         * The sentence, written out.
         *
         * The wording is a promise to the person reading it, so the test pins the
         * words instead of referring to the constant the screen renders: a change to
         * the copy breaks this test on purpose rather than by accident.
         */
        const val POINTER = "Tasks dated beyond next week are in the Calendar and the Plans timeline."
    }
}
