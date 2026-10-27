package com.singularity.todo.core.backup

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a change does to the base it applies to.
 *
 * ## Why this is tested on its own
 *
 * The merge is the one place where a chain is turned back into an account. Every
 * other part of the chain — what the export writes, what the push seals, what
 * the download hands back — only moves bytes around; the merge is what decides
 * what the account looks like afterwards.
 *
 * And the rule it follows is short enough to state in one line: **one row, one
 * key**. A row in the change with a key the base holds replaces it; a row with a
 * key the base does not hold is added; a row the change says nothing about is
 * kept.
 */
@Tag("fast")
class BackupDeltaMergeTest {

    /** A task, with a title that says which one it is. */
    private fun task(id: String, title: String) = TaskDto(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /**
     * A changed row replaces the row the base holds.
     *
     * The change carries whole rows, so the title that came with it is the
     * title the account has afterwards — not the base's, and not a blend of the
     * two.
     */
    @Test
    fun `a changed row replaces the row of the base`() {
        val base = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "before")))
        val delta = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "after")))

        val merged = merge(base, delta)

        assertEquals(1, merged.tasks.size, "the change replaces the row, it does not add one")
        assertEquals("after", merged.tasks.single().title)
    }

    /** A row the base never held is added. */
    @Test
    fun `a new row is added to the rows of the base`() {
        val base = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "before")))
        val delta = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-2", "new")))

        val merged = merge(base, delta)

        assertEquals(listOf("t-1", "t-2"), merged.tasks.map { it.id })
    }

    /**
     * A row the change says nothing about is kept.
     *
     * That is what makes a change a change and not a snapshot: the rows it does
     * not mention are still there.
     */
    @Test
    fun `a row the change does not mention is kept`() {
        val base = BackupPayload(
            schemaVersion = 1,
            tasks = listOf(task("t-1", "before"), task("t-2", "untouched")),
        )
        val delta = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "after")))

        val merged = merge(base, delta)

        assertEquals(listOf("t-1", "t-2"), merged.tasks.map { it.id })
        assertEquals("untouched", merged.tasks.last().title)
    }

    /**
     * The order of the base is the order of the merged account.
     *
     * A restore that reorders the rows it writes is a restore that makes a diff
     * of its own, and the diff is noise the person has to read.
     */
    @Test
    fun `the order of the base is kept`() {
        val base = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "a"), task("t-2", "b")))
        val delta = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-3", "c")))

        val merged = merge(base, delta)

        assertEquals(listOf("t-1", "t-2", "t-3"), merged.tasks.map { it.id })
    }

    /**
     * A link row is keyed by the pair it joins.
     *
     * A task's link to a tag is the pair, not the task: two links of one task to
     * two tags are two rows, and a key that only knew the task would collapse
     * them into one.
     */
    @Test
    fun `a link row is keyed by the pair it joins`() {
        val base = BackupPayload(
            schemaVersion = 1,
            taskTags = listOf(TaskTagDto(taskId = "t-1", tagId = "tag-1")),
        )
        val delta = BackupPayload(
            schemaVersion = 1,
            taskTags = listOf(TaskTagDto(taskId = "t-1", tagId = "tag-2")),
        )

        val merged = merge(base, delta)

        assertEquals(2, merged.taskTags.size, "both links of the task must survive")
        assertEquals(
            setOf("t-1", "tag-1"),
            merged.taskTags.first().let { setOf(it.taskId, it.tagId) },
        )
    }

    /**
     * A dependency edge is keyed by the pair it joins, too.
     *
     * The verb is part of the row and travels with it: an edge that changed its
     * meaning is a different edge, and the merge replaces it.
     */
    @Test
    fun `a dependency edge is keyed by the pair it joins`() {
        val base = BackupPayload(
            schemaVersion = 1,
            taskDependencies = listOf(TaskDependencyDto(taskId = "t-1", dependsOnTaskId = "t-2")),
        )
        val delta = BackupPayload(
            schemaVersion = 1,
            taskDependencies = listOf(
                TaskDependencyDto(taskId = "t-1", dependsOnTaskId = "t-2", verb = "FOLLOWS_UP"),
            ),
        )

        val merged = merge(base, delta)

        assertEquals(1, merged.taskDependencies.size, "the edge replaces the one of the base")
        assertEquals("FOLLOWS_UP", merged.taskDependencies.single().verb)
    }

    /**
     * A change with nothing in it leaves the base alone.
     *
     * This is what an export writes when the account did not move: the base is
     * still the truth, and the merged state is the base.
     */
    @Test
    fun `a change with nothing in it leaves the base alone`() {
        val base = BackupPayload(schemaVersion = 1, tasks = listOf(task("t-1", "before")))

        val merged = merge(base, BackupPayload(schemaVersion = 1))

        assertEquals(base.tasks, merged.tasks)
        assertTrue(merged.tasks.isNotEmpty())
    }
}
