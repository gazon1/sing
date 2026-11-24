package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Desktop automation for `AGENDA-10` — the future feed is a timeline of the
 * days that hold tasks.
 *
 * The clock is pinned, so "today" is the same day on both sides of the assertion
 * whatever day the suite runs. The scenario it carries is declared in
 * `scripts/src/traceability/scenarios/agenda/AGENDA-10.yaml`; the
 * `@DisplayName` above is the linkage.
 *
 * This class is the whole owner of the Upcoming timeline: it carries the claims
 * of the retired `AGENDA-09` as well (each task appears once, the grouping
 * control switches the feed and back without losing a task), because the
 * Maestro flow that used to carry them is gone (#3120).
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class UpcomingTimelineScenarioTest {
    private val today: LocalDate = LocalDate(2026, 9, 16)

    @Test
    @DisplayName("AGENDA-10 Upcoming date headers are chronological and an empty day has no header")
    fun days_in_chronological_order() =
        runDesktopAppTest(clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z")), checkA11y = true) { koin ->
            tasks(koin)
                .given(due = today, title = "Due today")
                .given(due = today.plus(1, DateTimeUnit.DAY), title = "Due tomorrow")
                .given(due = today.plus(3, DateTimeUnit.DAY), title = "Due in three days")
                .given(due = today.plus(6, DateTimeUnit.DAY), title = "Due in six days")
                .givenUndated(title = "Undated")

            tapTab("Upcoming")
            assertTagDisplayed(TestTags.AGENDA_GROUPING_DAY)
            onNode(hasTestTag(TestTags.AGENDA_GROUPING_DAY)).performClick()

            assertTagDisplayed(TestTags.agendaSection("Tomorrow"))
            assertTagDisplayed(TestTags.agendaSection("Sat, 19 Sep"))
            assertTagDisplayed(TestTags.agendaSection("Tue, 22 Sep"))
            assertTagDisplayed(TestTags.taskItem("Due tomorrow"))
            assertTagDisplayed(TestTags.taskItem("Due in three days"))
            assertTagDisplayed(TestTags.taskItem("Due in six days"))

            // Each task is listed once: a row that appeared under two dates
            // would satisfy "the task is on screen" and still be a wrong feed.
            assertDisplayedOnce(TestTags.taskItem("Due tomorrow"))
            assertDisplayedOnce(TestTags.taskItem("Due in three days"))
            assertDisplayedOnce(TestTags.taskItem("Due in six days"))

            // Today and the undated tasks belong to the other tabs, so the feed
            // does not repeat them.
            assertNotDisplayed(TestTags.taskItem("Due today"))
            assertNotDisplayed(TestTags.taskItem("Undated"))

            // The six days between two plans are a gap, not six empty headers.
            assertNotDisplayed(TestTags.agendaSection("Sun, 20 Sep"))

            // The headers are in date order: every header sits below the one
            // before it. Comparing only the first and the last would let the
            // middle one out of order — Tomorrow, Tue 22 Sep, Sat 19 Sep —
            // and still pass.
            val first = unclippedBoundsOfTag(TestTags.agendaSection("Tomorrow")).top
            val middle = unclippedBoundsOfTag(TestTags.agendaSection("Sat, 19 Sep")).top
            val last = unclippedBoundsOfTag(TestTags.agendaSection("Tue, 22 Sep")).top
            assertTrue(
                first < middle && middle < last,
                "the sections are not in chronological order: $first, $middle, $last",
            )

            // And each task sits under its own date: a task that drifted into
            // the neighbouring section would still be on screen, and still be
            // listed once.
            assertTaskUnderSection("Due tomorrow", "Tomorrow", "Sat, 19 Sep")
            assertTaskUnderSection("Due in three days", "Sat, 19 Sep", "Tue, 22 Sep")
            assertTaskUnderSection("Due in six days", "Tue, 22 Sep")
        }

    @Test
    fun `the grouping control switches the feed to weeks`() =
        runDesktopAppTest(clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z")), checkA11y = true) { koin ->
            tasks(koin)
                .given(due = today.plus(1, DateTimeUnit.DAY), title = "Due tomorrow")
                .given(due = today.plus(3, DateTimeUnit.DAY), title = "Due in three days")
                .given(due = today.plus(6, DateTimeUnit.DAY), title = "Due in six days")
                // The controls belong to the other tabs: a grouping change
                // that let them in would not be seen without them in the
                // fixture.
                .given(due = today, title = "Due today")
                .givenUndated(title = "Undated")

            tapTab("Upcoming")
            assertTagDisplayed(TestTags.AGENDA_GROUPING_DAY)
            onNode(hasTestTag(TestTags.AGENDA_GROUPING_DAY)).performClick()
            assertTagDisplayed(TestTags.agendaSection("Tomorrow"))

            onNode(hasTestTag(TestTags.AGENDA_GROUPING_WEEK)).performClick()

            assertTagDisplayed(TestTags.agendaSection("14–20 Sep"))
            assertTagDisplayed(TestTags.agendaSection("21–27 Sep"))
            assertNotDisplayed(TestTags.agendaSection("Tomorrow"))

            // The grouping changes the projection, not the set: the same three
            // tasks are still in the feed, once each.
            assertDisplayedOnce(TestTags.taskItem("Due tomorrow"))
            assertDisplayedOnce(TestTags.taskItem("Due in three days"))
            assertDisplayedOnce(TestTags.taskItem("Due in six days"))

            // And each task sits under its own week.
            assertTaskUnderSection("Due tomorrow", "14–20 Sep", "21–27 Sep")
            assertTaskUnderSection("Due in three days", "14–20 Sep", "21–27 Sep")
            assertTaskUnderSection("Due in six days", "21–27 Sep")

            // And the controls stay out of the feed under the other
            // projection too.
            assertNotDisplayed(TestTags.taskItem("Due today"))
            assertNotDisplayed(TestTags.taskItem("Undated"))

            // And back: the day headers return with the tasks under them. The
            // middle task is asserted too — a round trip that loses the task
            // in the middle of the feed loses a task and still shows the first
            // and the last.
            onNode(hasTestTag(TestTags.AGENDA_GROUPING_DAY)).performClick()

            assertTagDisplayed(TestTags.agendaSection("Tomorrow"))
            assertDisplayedOnce(TestTags.taskItem("Due tomorrow"))
            assertDisplayedOnce(TestTags.taskItem("Due in three days"))
            assertDisplayedOnce(TestTags.taskItem("Due in six days"))
            assertNotDisplayed(TestTags.taskItem("Due today"))
            assertNotDisplayed(TestTags.taskItem("Undated"))

            // And the round trip kept the rows under their own dates.
            assertTaskUnderSection("Due tomorrow", "Tomorrow", "Sat, 19 Sep")
            assertTaskUnderSection("Due in three days", "Sat, 19 Sep", "Tue, 22 Sep")
            assertTaskUnderSection("Due in six days", "Tue, 22 Sep")
        }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertNotDisplayed(tag: String) {
        val nodes = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        assertTrue(nodes.isEmpty(), "the feed must not show '$tag'")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertDisplayedOnce(tag: String) {
        val nodes = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        assertTrue(nodes.size == 1, "the feed must show '$tag' exactly once, saw ${nodes.size}")
    }

    /**
     * Asserts that the task [title] sits under the section [section].
     *
     * A task that drifted into the neighbouring section is still on screen and
     * still listed once, so the row's own position is the only thing that
     * says which date it belongs to: below the header of its own section and
     * above the header of the next one.
     *
     * @param title the title of the task, which is the row's tag source.
     * @param section the title of the section the task belongs under.
     * @param nextSection the title of the section that follows it, when there
     *   is one — the last section of the feed has no upper bound.
     */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertTaskUnderSection(
        title: String,
        section: String,
        nextSection: String? = null,
    ) {
        val taskTop = unclippedBoundsOfTag(TestTags.taskItem(title)).top
        val sectionTop = unclippedBoundsOfTag(TestTags.agendaSection(section)).top
        assertTrue(
            taskTop > sectionTop,
            "'$title' is not under '$section': the row is at $taskTop, the header at $sectionTop",
        )
        nextSection?.let { next ->
            val nextTop = unclippedBoundsOfTag(TestTags.agendaSection(next)).top
            assertTrue(
                taskTop < nextTop,
                "'$title' is not under '$section' but under '$next': " +
                    "the row is at $taskTop, the next header at $nextTop",
            )
        }
    }
}
