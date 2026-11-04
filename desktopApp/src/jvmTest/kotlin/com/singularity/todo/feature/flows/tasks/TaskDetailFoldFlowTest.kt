package com.singularity.todo.feature.flows.tasks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tasks
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The claim in #1754 is about the *task detail screen*, not about the editor
 * composable: "on a 360 dp phone, opening a task shows the due date and the
 * priority without scrolling".
 *
 * `TaskEditorContentOrderTest` mounts the editor with a stand-in section, so it
 * proves the order of the column the editor builds — it cannot prove anything
 * about the real screen, where the sections are real cards of real height. This
 * test opens a real task through the app shell, at a phone-width window, and asks
 * the two questions the issue asks:
 *
 * 1. are the due date and the priority displayed with no scroll, and
 * 2. is the task's own checklist section drawn below them?
 *
 * The second is the one that fails if the sections are moved back above the rows,
 * which is the regression this is here to catch.
 *
 * The window is 360x800 — a 1080x2400 phone is 360x800 dp — and not the suite's
 * usual 1024x768, because a fold claim measured against a 1024-wide window is not
 * a claim about a phone.
 *
 * Note the deliberate absence of a `scrollToTag` before the first two assertions:
 * `assertTagDisplayed` is the display check, and scrolling first would defeat the
 * entire point of the test. The section lookups below it use
 * `unclippedBoundsOfTag`, which reads a position, not a display, so a section
 * below the fold is compared where it actually is.
 *
 * Measurements printed here are the numbers quoted in the issues (#1940, #2009):
 * `./gradlew :desktopApp:test --tests "*TaskDetailFoldFlowTest" -i`.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class TaskDetailFoldFlowTest {
    @Test
    fun `the real detail screen shows the due date and the priority above the checklist`() =
        runDesktopAppTest(clock = CLOCK, width = PHONE_WIDTH, height = PHONE_HEIGHT) { koin ->
            tasks(koin).given(due = today, title = "Buy milk")

            awaitTag(TestTags.taskItem("Buy milk")).performClick()
            awaitTag(TestTags.TASK_EDITOR_TITLE_INPUT)

            // No scroll on purpose: "visible without scrolling" is the claim.
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)

            val priorityTop = topOf(TestTags.TASK_EDITOR_PRIORITY_ROW)
            val dueTop = topOf(TestTags.TASK_EDITOR_DUE_ROW)
            val projectTop = topOf(TestTags.TASK_EDITOR_PROJECT_ROW)
            val tagsTop = topOf(TestTags.TASK_EDITOR_TAGS_ROW)
            val checklistTop = topOf(TestTags.TASK_CHECKLIST_SECTION)
            val subtasksTop = topOf(TestTags.SUBTASKS_SECTION)

            println(
                "task detail @ ${PHONE_WIDTH}x$PHONE_HEIGHT dp — " +
                    "priority=$priorityTop due=$dueTop project=$projectTop tags=$tagsTop " +
                    "checklist=$checklistTop subtasks=$subtasksTop",
            )

            // The order, on the real screen: every attribute row above the child work.
            listOf(
                "priority" to priorityTop,
                "due date" to dueTop,
                "project" to projectTop,
                "tags" to tagsTop,
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

    private fun androidx.compose.ui.test.DesktopComposeUiTest.topOf(tag: String) =
        unclippedBoundsOfTag(tag).top

    private companion object {
        /** Mid-month, so no assertion in this file straddles a boundary. */
        val FIXED_NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
        val CLOCK: FakeClock = FakeClock(FIXED_NOW)
        val today: LocalDate = LocalDate(2026, 9, 16)

        /**
         * 360 x 800 dp — a 1080x2400 phone at 3x. The desktop test window is sized in
         * pixels, so at the suite's density of 1 these are dp.
         */
        const val PHONE_WIDTH = 360
        const val PHONE_HEIGHT = 800
    }
}
