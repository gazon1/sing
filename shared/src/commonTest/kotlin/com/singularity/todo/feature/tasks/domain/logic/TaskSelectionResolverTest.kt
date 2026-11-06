package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins what the named task selections select.
 *
 * ## Why this file exists
 *
 * The selection `active_not_deferred` was renamed from `inbox`, and the old name is no
 * longer accepted on the wire. A rename is only safe when the set it selects cannot
 * move under the new name. The repository's own reads are pinned by
 * `TaskRepositoryContractTest`; this file pins the *selection* — the rule the AI tool
 * applies to the live tasks, which is a combination of two filters (`NotDeferred` and
 * `ByStatuses(Active)`) that no repository read answers.
 *
 * Without it, the tool's answer could drift from the app's own rule and nothing would
 * say so: the tool would report a set that the name no longer describes.
 *
 * ## The rule being pinned
 *
 * `active_not_deferred` is every unfinished task that is not deferred — dated or
 * undated. The finished ones are out (completing a task takes it off the list of work
 * to do), the deferred ones are out (they are not work to do yet), the trashed ones
 * are out. `active` is the wider set: the same, with the deferred tasks kept.
 */
@Tag("fast")
class TaskSelectionResolverTest {
    private val today = LocalDate(2026, 10, 14)
    private val epoch = Instant.fromEpochMilliseconds(0)

    private val context =
        SelectorContext(
            today = today,
            now = epoch,
            blockedIds = emptySet(),
        )

    @Test
    fun `the active not deferred selection holds the unfinished tasks that are not deferred`() {
        val undated = task("undated")
        val dated = task("dated", dueDate = today)
        val deferred = task("deferred", someday = true)
        val finished = task("finished", completedAt = epoch)
        val trashed = task("trashed", archivedAt = epoch)

        assertTrue(selects(TaskSelection.ACTIVE_NOT_DEFERRED, undated), "an undated task is still to do")
        assertTrue(selects(TaskSelection.ACTIVE_NOT_DEFERRED, dated), "a dated task is still to do")
        assertFalse(selects(TaskSelection.ACTIVE_NOT_DEFERRED, deferred), "a deferred task is not work to do yet")
        assertFalse(selects(TaskSelection.ACTIVE_NOT_DEFERRED, finished), "a finished task is not still to do")
        assertFalse(selects(TaskSelection.ACTIVE_NOT_DEFERRED, trashed), "a trashed task is not the user's work")
    }

    @Test
    fun `the active selection keeps the deferred tasks the narrower selection drops`() {
        val deferred = task("deferred", someday = true)

        assertTrue(
            selects(TaskSelection.ACTIVE, deferred),
            "ACTIVE is every unfinished task, the deferred ones included",
        )
        assertFalse(
            selects(TaskSelection.ACTIVE_NOT_DEFERRED, deferred),
            "ACTIVE_NOT_DEFERRED is ACTIVE without the deferred tasks — one direction only",
        )
    }

    /**
     * The cursor names the selection the way the wire does.
     *
     * The fingerprint is what tells a cursor apart from another selection's cursor, so
     * it has to be keyed on the name that does not change when the code is renamed: the
     * wire name. Keyed on the constant's name, every rename of the enum would silently
     * invalidate the cursors already handed out — the reader would be told the cursor
     * was for a different selection and would start the list again.
     */
    @Test
    fun `the cursor fingerprint names the selection by its wire name`() {
        val fingerprint =
            TaskSelectionResolver.fingerprint(
                TaskSelectionRequest(selection = TaskSelection.ACTIVE_NOT_DEFERRED),
            )

        assertEquals(
            "active_not_deferred",
            fingerprint.substringBefore("|"),
            "the fingerprint names the selection by its wire name",
        )
        assertFalse(
            fingerprint.contains("ACTIVE_NOT_DEFERRED"),
            "the constant's name is the code's word, not the wire's — a rename of it must not move the fingerprint",
        )
    }

    /** Whether [task] belongs to the set [selection] names. */
    private fun selects(
        selection: TaskSelection,
        task: Task,
    ): Boolean =
        TaskSelectionResolver.matches(
            TaskSelectionRequest(selection = selection),
            task,
            context,
        )

    private fun task(
        id: String,
        dueDate: LocalDate? = null,
        completedAt: Instant? = null,
        archivedAt: Instant? = null,
        someday: Boolean = false,
    ): Task =
        Task(
            id = TaskId(id),
            title = "T $id",
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            dueDate = dueDate,
            completedAt = completedAt,
            archivedAt = archivedAt,
            someday = someday,
            createdAt = epoch,
            updatedAt = epoch,
            userId = UserId("test"),
        )
}
