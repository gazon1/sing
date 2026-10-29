package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagInMergedTree
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The `agenda_section_*` tags the Maestro flows address must exist in the
 * **merged** semantics tree, so a section header that gets folded into a
 * merging ancestor is caught on the JVM tier rather than on a device.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaSectionTagReachabilityFlowTest {

    @Test
    fun the_today_tab_exposes_the_today_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).given(due = TODAY, title = "Buy milk")

            assertTagInMergedTree(TestTags.agendaSection("Today"), "agenda")
        }

    @Test
    fun the_inbox_tab_exposes_the_no_date_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).givenUndated("Call the dentist")

            tapTab("Inbox")
            assertTagInMergedTree(TestTags.agendaSection("No Date"), "agenda")
        }

    @Test
    fun the_inbox_tab_exposes_the_overdue_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
            tasks(koin).given(due = yesterday, title = "Pay rent")

            tapTab("Inbox")
            assertTagInMergedTree(TestTags.agendaSection("Overdue"), "agenda")
        }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
    }
}