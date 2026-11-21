package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The Task Detail's completion control performs the write it offers, and the screen
 * renders the result.
 *
 * The headless contract proves that the *adapter*'s `toggle_complete` reaches
 * `TaskDetailCoordinator.onIntent` (`TaskDetailScreenIntrospectionTest`), and the
 * coordinator is proven to build from the real graph (`TaskDetailCoordinatorGraphTest`).
 * Neither says a control on the screen is wired to that boundary: a composable whose
 * `onClick` went nowhere would pass both. These two tests are the missing half — a tap on
 * the real control, the write it was supposed to produce, and the state the screen renders
 * from that write.
 *
 * ## The same dispatch boundary, and how much of it is proven
 *
 * Both paths end at `TaskDetailCoordinator.onIntent(intent: TaskDetailIntent.Domain)`:
 * the hero's control constructs `TaskDetailIntent.Domain.ToggleComplete`, and
 * `TaskDetailScreenIntrospection.intentFor` returns the same variant for `toggle_complete`.
 * That is a fact about the two call sites, and it is established by reading them, not by
 * this file. Nothing here observes the dispatch: comparing the constructed values needs the
 * coordinator instance the composition owns, and neither the app harness nor the isolated
 * one hands it out — exposing it would be a test-only seam, which is the custom DSL the
 * issue rules out.
 *
 * So what these tests prove is narrower, and worth stating exactly: a tap on the control
 * produces the write and the rendered state, and removing the control's callback removes
 * both. What they do **not** prove is that the write went through `onIntent` — a callback
 * that wrote to the repository directly, producing the same state, would pass them. The
 * difference matters for the effects the slot adds (a recurring task's close, the blocked-
 * close confirmation), which a direct write would skip. Closing that gap needs a seam, and
 * that is a separate decision, not something this test quietly claims.
 *
 * ## What else is verified, and what is not
 *
 * Covered elsewhere and deliberately not repeated: the priority row → picker → relabel
 * (`SetPriorityFlowTest`) and the checklist inline add-row (`TaskDetailEmptyChildSectionsTest`,
 * `TaskItemListScenarioTest`). Those assert rendered state without reading the write back;
 * a third copy here would add maintenance, not coverage.
 *
 * Known gaps, named so this suite is not read as an inventory of the screen:
 *
 * * A control with no adapter action: every row and section the detail shows that
 *   `describe()` does not publish. The snapshot is an allowlisted projection, not a promise
 *   of UI parity.
 * * An adapter action with no control of its own: `restore_task`. The undo is the snackbar's
 *   action, drawn by `NotificationHost` rather than by an attribute row, and the snackbar is
 *   covered by `TaskDetailOverflowActionsTest`.
 * * Unavailable states: the adapter refuses every action while the screen is `loading` or
 *   `error`, and both screens are asserted in the isolated tests. What is not asserted here
 *   is that *no* control offers an action in those states — that is a claim about the error
 *   screen this file does not mount.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailCompletionWiringTest {
    @Test
    @DisplayName("one tap on the hero's control completes the task, and the screen says so")
    fun one_tap_on_the_heroes_control_completes_the_task() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val taskId = seedAndOpen(koin, OPEN_TASK)

            // Open: the control offers to complete, and no completion date is drawn.
            assertContentDescriptionDisplayed(MARK_COMPLETE)

            clickContentDescription(MARK_COMPLETE)

            // The date is rendered from the task's own `completedAt` and from nothing else,
            // so its arrival is the rendered-state claim; the offer flipping is the control
            // following the same write. `assertTagDisplayed` carries both the presence wait
            // and the display wait — the bare `awaitTag` + one-shot assert is the race the
            // harness rules out.
            assertTagDisplayed(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            assertContentDescriptionDisplayed(COMPLETED)

            assertNotNull(
                taskRepository(koin).get(taskId)?.completedAt,
                "the completion control changed the icon and the date without writing: " +
                    "the tap never reached a write at all",
            )
        }

    @Test
    @DisplayName("one tap on a completed task's control reopens it")
    fun one_tap_on_a_completed_tasks_control_reopens_it() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
        ) { koin ->
            val taskId = seedAndOpen(koin, DONE_TASK, completed = true)

            assertTagDisplayed(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            assertContentDescriptionDisplayed(COMPLETED)

            clickContentDescription(COMPLETED)

            // Reopening clears the date. Waiting for it to go, not asserting its absence
            // once: the write is asynchronous, and a one-shot check races the emission.
            awaitTagGone(TestTags.TASK_DETAIL_COMPLETED_LABEL)
            assertContentDescriptionDisplayed(MARK_COMPLETE)

            assertNull(
                taskRepository(koin).get(taskId)?.completedAt,
                "the control rebuilt the screen without reopening the task",
            )
        }

    /**
     * Seeds [title] and leaves its detail settled on screen.
     *
     * The detail is opened by pressing the agenda row, so the caller's first tap is the
     * one under test, measured from a screen that has arrived rather than one still loading.
     *
     * @return the seeded task's id, so the caller can read the write back.
     */
    private suspend fun androidx.compose.ui.test.DesktopComposeUiTest.seedAndOpen(
        koin: Koin,
        title: String,
        completed: Boolean = false,
    ): TaskId {
        val taskId =
            requireNotNull(
                tasks(koin)
                    .given(
                        due = TODAY,
                        title = title,
                        completed = completed,
                        completedAt = if (completed) COMPLETED_AT else null,
                    ).lastTaskId,
            )
        tapTab("Today")
        awaitTag(TestTags.taskItem(title)).performClick()
        awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)
        return taskId
    }

    private fun taskRepository(koin: Koin): TaskRepository = koin.get()

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        val TEST_ZONE: TimeZone = TimeZone.UTC

        /**
         * Midday UTC on a Saturday, so no timezone that can reach this test rolls the date
         * across midnight and empties the Today tab.
         */
        val TEST_CLOCK = FakeClock(Instant.parse("2026-03-14T12:00:00Z"))
        val TODAY: LocalDate = todayAt(TEST_CLOCK, TEST_ZONE)

        /** Earlier than [TEST_CLOCK], so "completed" cannot be mistaken for "due today". */
        val COMPLETED_AT: Instant = Instant.parse("2026-03-10T09:00:00Z")

        /** Distinct titles, so one test's fixture cannot be the other's row. */
        const val OPEN_TASK = "Wiring open task"
        const val DONE_TASK = "Wiring done task"

        /**
         * The two announcements of the completion control, read from
         * `composeResources/values/strings.xml`.
         *
         * Resources, not literals in the composable — the harness mounts in English
         * (`useEnglishTestLocale`), so these are the copy this test reads. The Russian
         * bundle would be the Android device's answer, not the desktop tier's.
         */
        const val MARK_COMPLETE = "Mark as complete"
        const val COMPLETED = "Task completed"
    }
}
