package com.singularity.todo.test.helpers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.singularity.todo.core.ui.TestTags
import kotlinx.datetime.LocalDate
import org.koin.core.Koin

/**
 * Fluent access to task preconditions and assertions, so a flow reads as
 * "given … when … then" instead of a sequence of helper calls with a Koin
 * reference threaded through each one.
 *
 * ```kotlin
 * runDesktopAppTest { koin ->
 *     tasks(koin)
 *         .given(due = today, title = "Buy milk")
 *         .assertInAgenda("Buy milk")
 * }
 * ```
 *
 * ## Why [given] has no default for `due`
 *
 * The undated path is an open question, not a settled one. The domain half is
 * pinned green (`AgendaNoDateRegressionTest`), but the layers above
 * `AgendaEvaluator` are not yet exonerated — see
 * `2026-09-30-nodate-root-cause.md` and `deferred-backlog.md#nodate-steps-2-4`.
 * A test that seeds an undated task and then asserts it is listed is therefore
 * asserting on behaviour that is not yet decided, and when it fails the message
 * points at the assertion rather than at the real cause.
 *
 * Defaulting `due` to today would settle that silently: the task appears, the
 * test passes, and the undated path gets neither accidental nor deliberate
 * coverage. Making the argument mandatory forces a choice, and [givenUndated] is
 * how the undated case is asked for — by a name that `grep` can find, so the
 * tests standing on unsettled ground are enumerable.
 *
 * [Koin.seedTask] keeps its nullable `dueDate` for the call sites that predate
 * this robot; new code should go through [given].
 */
@OptIn(ExperimentalTestApi::class)
class TasksRobot(
    private val test: DesktopComposeUiTest,
    private val koin: Koin,
) {
    private var seq = 0

    /** A task the agenda will render: dated, because that is the path that works today. */
    suspend fun given(
        due: LocalDate,
        title: String = "Buy milk",
        completed: Boolean = false,
    ): TasksRobot = apply {
        koin.seedTask(
            id = nextId(),
            title = title,
            dueDate = due,
            completed = completed,
        )
    }

    /**
     * An undated task, on purpose.
     *
     * Named differently from [given] so the choice is visible at the call site
     * and greppable: `grep givenUndated` lists every test that deliberately sits
     * on the broken path.
     */
    suspend fun givenUndated(title: String = "Buy milk"): TasksRobot = apply {
        koin.seedTask(id = nextId(), title = title, dueDate = null)
    }

    /** Asserts the task is rendered in the agenda, waiting for the list to settle. */
    fun assertInAgenda(title: String): TasksRobot = apply {
        test.awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
    }

    /** Waits for the task row and clicks it, returning the row for further work. */
    fun open(title: String): SemanticsNodeInteraction =
        test.awaitTag(TestTags.taskItem(title)).also { it.performClick() }

    private fun nextId(): String = "robot-task-${seq++}"
}

/** Entry point: `tasks(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tasks(koin: Koin): TasksRobot =
    TasksRobot(this, koin)
