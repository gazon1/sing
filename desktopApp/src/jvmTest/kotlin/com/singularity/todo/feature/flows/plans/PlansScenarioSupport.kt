package com.singularity.todo.feature.flows.plans

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.countNodes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.time.Instant

/**
 * Shared fixtures for the Plans scenarios `PLANS-01` … `PLANS-10`.
 *
 * The traceability rule is one scenario per test file: two display-name
 * annotations carrying two scenario ids in one file are reported as two
 * scenarios claimed by one file. The scenarios therefore live in their own
 * `Plans…ScenarioTest` carriers, and everything they both need — the pinned
 * clock, the dates, the row helper — lives here, where no scenario id is
 * claimed. The file names no scenario and carries no display-name annotation,
 * so the traceability scanner does not read it as a claimant.
 *
 * The clock is pinned, so "today" is the same day on both sides of the
 * assertion whatever day the suite runs.
 */
internal object PlansScenarioSupport {
    val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    val CLOCK: FakeClock = FakeClock(FIXED_NOW)
    val TODAY: LocalDate = LocalDate(2026, 9, 16)
    val YESTERDAY: LocalDate = TODAY.minus(1, DateTimeUnit.DAY)
}

/** The ids the grouper uses for the section that holds the late work. */
internal object TimelineGrouperIds {
    const val OVERDUE = "overdue"
}

/**
 * The row of [title] appears exactly once.
 *
 * Counted, not looked at: a single stray row is invisible to the eye in a long
 * list, and a duplicated row is the failure this scenario is about.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.assertOneRow(title: String) {
    val rows = countNodes(TestTags.taskItem(title))
    kotlin.test.assertEquals(1, rows, "the task \"$title\" is listed $rows times, not once")
}
