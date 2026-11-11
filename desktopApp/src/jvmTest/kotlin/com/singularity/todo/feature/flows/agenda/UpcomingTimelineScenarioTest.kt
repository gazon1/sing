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

            // Today and the undated tasks belong to the other tabs, so the feed
            // does not repeat them.
            assertNotDisplayed(TestTags.taskItem("Due today"))
            assertNotDisplayed(TestTags.taskItem("Undated"))

            // The six days between two plans are a gap, not six empty headers.
            assertNotDisplayed(TestTags.agendaSection("Sun, 20 Sep"))

            // The headers are in date order: the later day sits lower.
            val first = unclippedBoundsOfTag(TestTags.agendaSection("Tomorrow")).top
            val last = unclippedBoundsOfTag(TestTags.agendaSection("Tue, 22 Sep")).top
            assertTrue(
                first < last,
                "the sections are not in chronological order: $first is not above $last",
            )
        }

    @Test
    fun `the grouping control switches the feed to weeks`() =
        runDesktopAppTest(clock = FakeClock(Instant.parse("2026-09-16T10:00:00Z")), checkA11y = true) { koin ->
            tasks(koin)
                .given(due = today.plus(1, DateTimeUnit.DAY), title = "Due tomorrow")
                .given(due = today.plus(3, DateTimeUnit.DAY), title = "Due in three days")
                .given(due = today.plus(6, DateTimeUnit.DAY), title = "Due in six days")

            tapTab("Upcoming")
            assertTagDisplayed(TestTags.AGENDA_GROUPING_DAY)
            onNode(hasTestTag(TestTags.AGENDA_GROUPING_DAY)).performClick()
            assertTagDisplayed(TestTags.agendaSection("Tomorrow"))

            onNode(hasTestTag(TestTags.AGENDA_GROUPING_WEEK)).performClick()

            assertTagDisplayed(TestTags.agendaSection("14–20 Sep"))
            assertTagDisplayed(TestTags.agendaSection("21–27 Sep"))
            assertNotDisplayed(TestTags.agendaSection("Tomorrow"))
        }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.DesktopComposeUiTest.assertNotDisplayed(tag: String) {
        val nodes = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        assertTrue(nodes.isEmpty(), "the feed must not show '$tag'")
    }
}
