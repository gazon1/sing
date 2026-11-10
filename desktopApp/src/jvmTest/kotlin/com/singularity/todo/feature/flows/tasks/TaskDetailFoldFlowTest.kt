package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The claim in #1754 is about the *task detail screen*, not about the editor composable:
 * "on a 360 dp phone, opening a task shows the due date and the priority without
 * scrolling".
 *
 * `TaskEditorSlotsOrderTest` mounts the editor with a stand-in section, so it proves the
 * order of the column the editor builds — it cannot prove anything about the real screen,
 * where the sections are real cards of real height. This test opens a real task through
 * the app shell, at a phone-width window, and asks the two questions the issue asks:
 *
 * 1. are the due date and the priority displayed with no scroll, and
 * 2. is the task's own checklist section drawn below them?
 *
 * The second is the one that fails if the sections are moved back above the rows, which
 * is the regression this is here to catch.
 *
 * The window is 360x800 — a 1080x2400 phone is 360x800 dp — and not the suite's usual
 * 1024x768, because a fold claim measured against a 1024-wide window is not a claim about
 * a phone.
 *
 * Note the deliberate absence of a `scrollToTag` before the first two assertions:
 * `assertTagDisplayed` is the display check, and scrolling first would defeat the entire
 * point of the test. The section lookups below it use `unclippedBoundsOfTag`, which reads
 * a position, not a display, so a section below the fold is compared where it actually is.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailFoldFlowTest {
    @Test
    fun the_real_detail_screen_shows_the_due_date_and_the_priority_above_the_checklist() =
        runDesktopAppTest(
            clock = TEST_CLOCK,
            overrides = module { single<TimeZoneProvider> { TestTimeZone } },
            checkA11y = true,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT,
        ) { koin ->
            tasks(koin).given(due = today, title = "Buy milk")

            tapTab("Today")
            awaitTag(TestTags.taskItem("Buy milk")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // No scroll on purpose: "visible without scrolling" is the claim.
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)

            val priorityTop = topOf(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val dueTop = topOf(TestTags.TASK_EDITOR_DUE_ROW)
            val projectTop = topOf(TestTags.TASK_EDITOR_PROJECT_ROW)
            val tagsTop = topOf(TestTags.TASK_EDITOR_TAGS_ROW)
            val pinTop = topOf(TestTags.TASK_EDITOR_PIN_ROW)
            val checklistTop = topOf(TestTags.TASK_CHECKLIST_SECTION)
            val subtasksTop = topOf(TestTags.SUBTASKS_SECTION)

            println(
                "task detail @ ${PHONE_WIDTH}x$PHONE_HEIGHT dp — " +
                    "priority=$priorityTop due=$dueTop project=$projectTop tags=$tagsTop " +
                    "pin=$pinTop checklist=$checklistTop subtasks=$subtasksTop",
            )

            // The order, on the real screen: every attribute row above the child work.
            // The pin row is the last of them, so it is the boundary that would fail
            // if the sections were moved between the rows.
            listOf(
                "priority" to priorityTop,
                "due date" to dueTop,
                "project" to projectTop,
                "tags" to tagsTop,
                "pin" to pinTop,
            ).forEach { (name, top) ->
                assertTrue(
                    top < checklistTop,
                    "The $name row ($top) must be drawn above the checklist section " +
                        "($checklistTop) on the real detail screen — the sections belong " +
                        "after the attribute rows.",
                )
                assertTrue(
                    top < subtasksTop,
                    "The $name row ($top) must be drawn above the subtasks section " +
                        "($subtasksTop) on the real detail screen.",
                )
            }
        }

    private fun DesktopComposeUiTest.topOf(tag: String) = unclippedBoundsOfTag(tag).top

    private object TestTimeZone : TimeZoneProvider {
        override fun current(): TimeZone = TEST_ZONE
    }

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val TEST_CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val TEST_ZONE: TimeZone = TimeZone.UTC
        val today: LocalDate = todayAt(TEST_CLOCK, TEST_ZONE)

        /**
         * 360 x 800 dp — a 1080x2400 phone at 3x. The desktop test window is sized in
         * pixels, and the suite's density is 1, so these are dp.
         */
        const val PHONE_WIDTH = 360
        const val PHONE_HEIGHT = 800
    }
}
