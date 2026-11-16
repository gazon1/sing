package com.singularity.todo.feature.flows.statistics

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.statistics.StatisticsDeps
import com.singularity.todo.feature.statistics.StatisticsViewModel
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
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
 * - the attention items are on that tab, one row per item, with a stable id
 * - setting an item aside takes the row off the screen
 * - the list says how many items it did not show, in every state
 * - the list stays on screen when the counts behind it fail
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class StatisticsHealthTabFlowTest {
    @Test
    fun the_health_tab_is_reachable_and_carries_its_own_id() =
        runDesktopAppTest(
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

    /**
     * The attention items reach the Health tab, and setting one aside takes it
     * off the screen.
     *
     * The row carries the item's own key, so a test can name the row it means
     * rather than counting rows — and the dismissal is asserted on the same
     * row, which is the only thing that says the dismissal removed *that* item
     * and not another one.
     *
     * The two rows are also what pins the day: the overdue task is late in the
     * injected zone, and the due-soon task is due tomorrow in it — which in the
     * machine's own zone is two days ahead and would not be on the list at all.
     */
    @Test
    fun the_attention_items_are_on_the_health_tab_and_can_be_set_aside() =
        runDesktopAppTest(
            clock = CLOCK,
            overrides = timeZoneOverride,
            checkA11y = true,
        ) { koin ->
            seedAttentionTasks(koin)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            assertTagDisplayed(TestTags.attentionNudge(OVERDUE_KEY))
            assertTagDisplayed(TestTags.attentionNudge(DUE_SOON_KEY))

            clickTag(TestTags.attentionNudgeDismiss(OVERDUE_KEY))

            awaitTagGone(TestTags.attentionNudge(OVERDUE_KEY))
        }

    /**
     * A dismissal does not survive leaving the screen.
     *
     * The dismissal is a statement about this visit to the screen, not a
     * setting: the user sets an item aside for now, and "now" ends when they
     * walk away from the screen. The navigation keeps the Statistics entry
     * alive while another destination is on top of it, so a dismissal held in
     * the ViewModel would still be there on the way back — the item the user
     * set aside would stay set aside, and the list would keep lying about
     * what needs attention.
     */
    @Test
    fun a_dismissal_does_not_survive_leaving_the_screen() =
        runDesktopAppTest(
            clock = CLOCK,
            overrides = timeZoneOverride,
            checkA11y = true,
        ) { koin ->
            seedAttentionTasks(koin)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)
            assertTagDisplayed(TestTags.attentionNudge(OVERDUE_KEY))

            clickTag(TestTags.attentionNudgeDismiss(OVERDUE_KEY))
            awaitTagGone(TestTags.attentionNudge(OVERDUE_KEY))

            // Leave the screen and come back to it.
            tapTab("Inbox")
            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            // The item the user set aside is on the list again.
            assertTagDisplayed(TestTags.attentionNudge(OVERDUE_KEY))
        }

    /**
     * The list says how many items it did not show.
     *
     * A shortlist that hides its own size reads as "that is all of it", and it
     * is not: seven items are late and the list shows five.
     */
    @Test
    fun the_list_says_how_many_items_it_did_not_show() =
        runDesktopAppTest(
            clock = CLOCK,
            overrides = timeZoneOverride,
            checkA11y = true,
        ) { koin ->
            seedOverdueTasks(koin, count = 7)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            assertTagTextEquals(TestTags.ATTENTION_NUDGES_MORE, "and 2 more")
        }

    /**
     * The count survives an emptied list.
     *
     * The user set every item the list showed aside, and the list shows none
     * of them now. The count is what the list owes the user in every state —
     * a count that vanished as the list emptied would be a count that only
     * appeared when it was convenient.
     */
    @Test
    fun the_count_survives_an_emptied_list() =
        runDesktopAppTest(
            clock = CLOCK,
            overrides = timeZoneOverride,
            checkA11y = true,
        ) { koin ->
            seedOverdueTasks(koin, count = 2)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            listOf("task:count-1", "task:count-2").forEach { key ->
                clickTag(TestTags.attentionNudgeDismiss(key))
                awaitTagGone(TestTags.attentionNudge(key))
            }

            assertTagTextEquals(TestTags.ATTENTION_NUDGES_MORE, "2 items are not shown right now")
        }

    /**
     * The list stays on screen when the counts behind it fail.
     *
     * The list and the counts are two readings of the same rows, and each is read
     * by its own query. The tab used to return early when the health query
     * failed, which took a list that had been read successfully off the screen —
     * the collector had its own state, and the screen pretended it did not.
     *
     * So the failure of one query must not hide the other's answer, and the
     * failure must be said: "Task health unavailable" is not the same answer as
     * an empty list, and the user cannot tell them apart if the tab shows
     * neither.
     */
    @Test
    fun the_attention_list_stays_when_the_counts_fail() =
        runDesktopAppTest(
            clock = CLOCK,
            overrides = healthUnavailableOverride,
            checkA11y = true,
        ) { koin ->
            seedAttentionTasks(koin)

            tapTab("Statistics")
            clickTag(TestTags.STATISTICS_TAB_HEALTH)

            // The list is there …
            assertTagDisplayed(TestTags.attentionNudge(OVERDUE_KEY))
            // … and the failure of the counts is said, not swallowed.
            assertTextDisplayed("Task health unavailable")
        }

    /**
     * The two tasks the list names: one whose due date has passed, and one due
     * tomorrow in the zone the screen reads.
     */
    private suspend fun seedAttentionTasks(koin: Koin) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val overdue =
            testTask(
                id = TaskId(OVERDUE_TASK_ID),
                title = "Late task",
                dueDate = today.minus(1, DateTimeUnit.DAY),
                userId = userId,
            )
        val dueSoon =
            testTask(
                id = TaskId(DUE_SOON_TASK_ID),
                title = "Task due tomorrow",
                dueDate = today.plus(1, DateTimeUnit.DAY),
                userId = userId,
            )
        val repository = koin.get<TaskRepository>()
        repository.upsert(TaskDocument.of(overdue))
        repository.upsert(TaskDocument.of(dueSoon))
    }

    /**
     * Tasks whose due date has passed — the first thing the list names.
     *
     * The ids carry their number, so the order the list shows them in is the
     * order the test expects: same kind, same date, and the key breaks the tie.
     */
    private suspend fun seedOverdueTasks(
        koin: Koin,
        count: Int,
    ) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        val repository = koin.get<TaskRepository>()
        repeat(count) { index ->
            val task =
                testTask(
                    id = TaskId("count-${index + 1}"),
                    title = "Late task ${index + 1}",
                    dueDate = today.minus(1, DateTimeUnit.DAY),
                    userId = userId,
                )
            repository.upsert(TaskDocument.of(task))
        }
    }

    /**
     * The zone the screen reads the day in.
     *
     * The screen asks the injected [TimeZoneProvider] for the zone, so the test
     * replaces it with a fixed one — a test that read the machine's own zone
     * would answer with a different day on every machine it runs on, and would
     * pin the old behaviour instead of the contract.
     */
    private object FixedZone : TimeZoneProvider {
        override fun current(): TimeZone = ZONE
    }

    /**
     * Settings whose staleness window cannot be read.
     *
     * The counts read that window from the settings, so a failure of that read
     * is what makes the counts unavailable. The list reads the tasks and the
     * projects and nothing else — which is the point of the test: the two
     * readings fail on their own.
     *
     * The rest of the settings are the real ones: only the one read is broken,
     * so the test does not describe a different app.
     */
    private class UnreadableStalenessSettings(delegate: SettingsRepository) : SettingsRepository by delegate {
        override val stalenessThresholdDays: Flow<Int> =
            flow { throw IllegalStateException("the staleness window cannot be read") }
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)

        /**
         * UTC+14, so that at [FIXED_NOW] the day here is already the next day.
         *
         * A task due tomorrow in this zone is two days ahead in the machine's
         * own zone, and would not be on the list at all — so the rows this test
         * waits for only appear if the screen read the injected zone.
         */
        val ZONE: TimeZone = TimeZone.of("Pacific/Kiritimati")

        /**
         * The day the Statistics screen sees.
         *
         * The screen reads the day in the injected zone, and the test injects a
         * fixed one — a hard-coded day would disagree with the screen on every
         * machine that is not in that zone.
         */
        val today: LocalDate = FIXED_NOW.toLocalDateTime(ZONE).date

        /** The zone the screen reads, swapped in for the machine's own. */
        val timeZoneOverride: Module = module { single<TimeZoneProvider> { FixedZone } }

        /**
         * The Health tab with the counts broken and the list intact.
         *
         * The ViewModel is replaced rather than the settings, because the
         * settings are read by more than the counts — replacing them would
         * describe a different app. The replacement reads the same settings,
         * except for the one read the counts depend on.
         *
         * The zone is replaced here too: the replacement ViewModel is built from
         * the same graph, and the graph's own zone is the machine's.
         */
        val healthUnavailableOverride: Module =
            module {
                single<TimeZoneProvider> { FixedZone }
                viewModel<StatisticsViewModel> {
                    StatisticsViewModel(
                        deps =
                            StatisticsDeps(
                                taskRepository = get(),
                                timeTrackingRepo = get(),
                                projectsRepo = get(),
                                currentUser = get(),
                                clock = get(),
                                settingsRepository = UnreadableStalenessSettings(get()),
                                timeZone = get(),
                            ),
                        crashReporter = get(),
                    )
                }
            }

        const val OVERDUE_TASK_ID = "nudge-overdue"
        const val DUE_SOON_TASK_ID = "nudge-due-soon"
        const val OVERDUE_KEY = "task:$OVERDUE_TASK_ID"
        const val DUE_SOON_KEY = "task:$DUE_SOON_TASK_ID"
    }
}
