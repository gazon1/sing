package com.singularity.todo.feature.flows.statistics

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import org.junit.jupiter.api.Tag
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
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class StatisticsHealthTabFlowTest {

    @Test
    fun the_health_tab_is_reachable_and_carries_its_own_id() = runDesktopAppTest(
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

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
    }
}
