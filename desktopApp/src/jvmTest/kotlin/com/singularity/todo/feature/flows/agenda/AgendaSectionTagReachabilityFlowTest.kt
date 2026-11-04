package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagInMergedTree
import com.singularity.todo.test.helpers.clickTag
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
 *
 * ## The two drill-down tests cover the header tag and the navigation, not the selector
 *
 * The two health drill-downs assert exactly two things: the Statistics bucket
 * click reaches the agenda, and the section header is addressable in the merged
 * tree once it is there. The header *text* comes from the static preset
 * (`AgendaPresets.byBlocked()` / `AgendaPresets.byStagnant()`), which renders
 * the header whether or not a task matches its selector — so seeding a
 * blocker/blocked pair or an old undated task here would look like it proved the
 * selector while proving nothing: the assertion would pass on the header alone,
 * with the fixture sitting in the tree unread. What the presets select is
 * covered where the selector lives; what cannot be covered there is whether the
 * tag survives the merge, and whether a desktop bucket click navigates at all,
 * which is what this class adds.
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

    /**
     * The overdue section belongs to the Today tab — the inbox holds the undated
     * tasks, and the three tabs are disjoint.
     */
    @Test
    fun the_today_tab_exposes_the_overdue_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
            tasks(koin).given(due = yesterday, title = "Pay rent")

            tapTab("Today")
            assertTagInMergedTree(TestTags.agendaSection("Overdue"), "agenda")
        }

    /**
     * A url-less bucket card is only a label unless clicking it lands on the
     * Blocked preset. The desktop host does wire that path: `JvmNavEntries`
     * registers `appEntries(nav = …)`, and `AppEntries` passes that
     * `NavCallbacks` into `StatisticsScreen`, so the click navigates instead of
     * falling into the `navCallbacks?.navigate(…)` no-op a missing host would
     * produce. That is why this test can exist at all rather than hang waiting
     * for a header that is never composed.
     *
     * No task is seeded — see the class doc: the header comes from the preset,
     * not from a match.
     */
    @Test
    fun the_blocked_health_drilldown_exposes_the_blocked_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)
            clickTag(TestTags.HEALTH_BUCKET_BLOCKED)

            assertTagInMergedTree(TestTags.agendaSection("Blocked"), "agenda")
        }

    /** Same contract as the blocked drill-down, on the other bucket card. */
    @Test
    fun the_stagnant_health_drilldown_exposes_the_stagnant_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) {
            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)
            clickTag(TestTags.HEALTH_BUCKET_STAGNANT)

            assertTagInMergedTree(TestTags.agendaSection("Not touched in a while"), "agenda")
        }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)
    }
}
