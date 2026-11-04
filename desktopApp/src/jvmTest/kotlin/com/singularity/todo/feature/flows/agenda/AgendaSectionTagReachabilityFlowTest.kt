package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagInMergedTree
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The `agenda_section_*` tags the Maestro flows address must exist in the
 * **merged** semantics tree, so a section header that gets folded into a
 * merging ancestor is caught on the JVM tier rather than on a device.
 *
 * ## Every test here seeds a task, because an empty section is not rendered
 *
 * `AgendaEvaluator.evaluate` drops a section whose selector matches nothing —
 * `if (matched.isEmpty()) return@mapNotNull null` (`AgendaEvaluator.kt:72`) —
 * and `AgendaEvaluatorTest` pins exactly that with `evaluate skips empty
 * sections`. A section header therefore only exists once something matches its
 * selector, so a header assertion with no fixture would not prove the tag
 * survives the merge; it would wait out `assertTagInMergedTree`'s timeout and
 * fail on the failure path, which is the most expensive way to be wrong. So
 * each drill-down below seeds the one task its preset selects, and the header
 * is composed for a reason rather than by assumption.
 *
 * What the presets *select* is still covered where the selector lives; what
 * cannot be covered there is whether the tag survives the merge, and whether a
 * desktop bucket click navigates at all, which is what this class adds.
 *
 * `assertTagInMergedTree` polls the merged tree (`waitUntil`,
 * `MergedTreeAssertions.kt:58-66`) rather than sampling it once, so a
 * drill-down's navigation settling is absorbed by the assertion instead of by
 * a sleep.
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
     * Blocked preset. The desktop host does wire that path:
     * `JvmNavEntries.kt:90` calls `appEntries(nav, …)`, and `AppEntries.kt:46`
     * passes that `NavCallbacks` into `StatisticsScreen(navCallbacks = nav)`, so
     * the click navigates instead of falling into the
     * `navCallbacks?.navigate(…)` no-op a host without nav would produce.
     *
     * That wiring is asserted rather than assumed: [assertDrilldownLeftStatistics]
     * runs before the header assertion, so a dead click fails naming the missing
     * navigation, not three steps later as a missing agenda header.
     *
     * A dependency edge is seeded because `Selector.Blocked` matches only a task
     * an unfinished prerequisite points at, and an empty section is dropped
     * before its header can render — see the class doc.
     */
    @Test
    fun the_blocked_health_drilldown_exposes_the_blocked_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            koin.seedBlockedPair()

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)
            clickTag(TestTags.HEALTH_BUCKET_BLOCKED)

            assertDrilldownLeftStatistics()
            assertTagInMergedTree(TestTags.agendaSection("Blocked"), "agenda")
        }

    /**
     * Same contract as the blocked drill-down, on the other bucket card.
     *
     * The fixture has to be swept for staleness: `Selector.Stagnant` matches an
     * undated, unfinished, unblocked task whose last activity is at least 21
     * days before now. `TasksRobot` stamps `updatedAt` at the epoch, so
     * `givenUndated` lands decades on the far side of the threshold — which is
     * why it is the fixture here, not an incidental detail.
     */
    @Test
    fun the_stagnant_health_drilldown_exposes_the_stagnant_section_in_the_merged_tree() =
        runDesktopAppTest(clock = CLOCK, checkA11y = true) { koin ->
            tasks(koin).givenUndated("An idea from a past life")

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)
            clickTag(TestTags.HEALTH_BUCKET_STAGNANT)

            assertDrilldownLeftStatistics()
            assertTagInMergedTree(TestTags.agendaSection("Not touched in a while"), "agenda")
        }

    /**
     * Asserts the bucket click pushed the agenda, i.e. that the statistics
     * screen stopped rendering.
     *
     * Without this, a drill-down's only failure mode is "the header never
     * appeared", which reads as an agenda bug whether the cause is a missing
     * header tag, a selector that matched nothing, or a click that never
     * navigated. Failing here first names the navigation — the condition the
     * brief says the desktop path has to establish.
     *
     * Nav3 composes only `stack.top`, so the pushed entry replaces the
     * statistics screen rather than sitting beside it: `STATISTICS_TAB_HEALTH`
     * is absent once the push lands, and still present while the click is inert.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertDrilldownLeftStatistics() =
        waitUntil(
            conditionDescription =
                "the statistics screen is gone after the health bucket click " +
                    "(the click did not navigate)",
            timeoutMillis = DRILLDOWN_TIMEOUT_MS,
        ) {
            onAllNodesWithTag(TestTags.STATISTICS_TAB_HEALTH).fetchSemanticsNodes().isEmpty()
        }

    /**
     * Seeds the one dependency edge `Selector.Blocked` needs: `robot-blocked`
     * waits on `robot-blocker`, which is unfinished and untrashed, so
     * `Blocking.blockedTaskIds` reports `robot-blocked` as blocked.
     *
     * Seeded through the repository rather than `tasks(koin).given(…)` because
     * `TasksRobot` has no `blockedBy` parameter, and the edge is the whole
     * fixture: without it the Blocked section holds nothing and `evaluate` drops
     * the section before its header can be composed.
     */
    private suspend fun Koin.seedBlockedPair() {
        val userId = get<ProfileAwareCurrentUser>().scopedUserId.value
        val at = Instant.parse("2026-09-01T00:00:00Z")

        fun task(
            id: String,
            title: String,
            blockedBy: List<TaskId>,
        ) = testTask(
            id = TaskId(id),
            title = title,
            dueDate = null,
            userId = userId,
            createdAt = at,
            updatedAt = at,
        ).copy(blockedBy = blockedBy)

        val blocker = TaskId("robot-blocker")
        val repository = get<TaskRepository>()
        repository.upsert(TaskDocument.of(task(blocker.value, "Draft the spec", emptyList())))
        repository.upsert(
            TaskDocument.of(task("robot-blocked", "Publish the spec", listOf(blocker))),
        )
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TODAY: LocalDate = LocalDate(2026, 9, 16)

        /**
         * Deliberately shorter than `assertTagInMergedTree`'s timeout: a
         * navigation either lands within a frame or two or it never will, and
         * the point of this check is to fail fast and name the cause.
         */
        const val DRILLDOWN_TIMEOUT_MS = 5_000L
    }
}
