package com.singularity.todo.test.helpers

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.testTask
import kotlinx.datetime.LocalDate
import org.koin.core.Koin
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
 */
@OptIn(ExperimentalTestApi::class)
class TasksRobot(
    private val test: DesktopComposeUiTest,
    private val koin: Koin,
) {
    private var seq = 0
    private var seeded = 0

    /** A task the agenda will render: dated, because that is the path that works today. */
    suspend fun given(
        due: LocalDate,
        title: String = "Buy milk",
        completed: Boolean = false,
    ): TasksRobot = apply {
        seed(title = title, dueDate = due, completed = completed)
        // The seed is the precondition, so verify it landed before any UI
        // assertion can run. Without this, a failed flow reads as "the screen
        // does not render the task" when the repository never received it —
        // two different bugs with one useless message. This is the MR-2
        // layer-assertion convention, applied at the only place every
        // robot-based flow passes through.
        assertSeeded(koin, expected = ++seeded)
    }

    /**
     * An undated task, on purpose.
     *
     * Named differently from [given] so the choice is visible at the call site
     * and greppable: `grep givenUndated` lists every test that deliberately sits
     * on the broken path.
     */
    suspend fun givenUndated(title: String = "Buy milk"): TasksRobot = apply {
        seed(title = title, dueDate = null)
        assertSeeded(koin, expected = ++seeded)
    }

    /** Asserts the task is rendered in the agenda, waiting for the list to settle. */
    fun assertInAgenda(title: String): TasksRobot = apply {
        test.awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
    }

    /** Waits for the task row and clicks it, returning the row for further work. */
    fun open(title: String): SemanticsNodeInteraction =
        test.awaitTag(TestTags.taskItem(title)).also { it.performClick() }

    private suspend fun seed(
        title: String,
        dueDate: LocalDate?,
        completed: Boolean = false,
    ) {
        // Seed timestamps come off a fixed epoch stepped by the sequence number,
        // not the wall clock: two tasks seeded in one flow must have a stable
        // relative order, and a flow's result must not depend on the hour it ran.
        // Same convention as NotesScreenTest / TagsRenameUiTest.
        val n = seq++
        val at = SEED_EPOCH + n.seconds
        koin.get<TaskRepository>().upsert(
            testTask(
                id = TaskId("robot-task-$n"),
                title = title,
                dueDate = dueDate,
                completedAt = if (completed) at else null,
                userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    private companion object {
        val SEED_EPOCH = Instant.fromEpochMilliseconds(0)
    }
}

/** Entry point: `tasks(koin)` inside a `runDesktopAppTest` body. */
@OptIn(ExperimentalTestApi::class)
fun DesktopComposeUiTest.tasks(koin: Koin): TasksRobot =
    TasksRobot(this, koin)
