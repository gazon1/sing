package com.singularity.todo.feature.auth

import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.database.ProjectEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Removing one account's rows must leave no orphans behind.
 *
 * Four tables carry no `user_id` and no foreign keys, so they are selected through their
 * parent. Delete a parent first and its children are orphaned: unreachable rows that
 * belong to neither the account that left nor the account that arrives, and that a later
 * sign-in can collide with.
 *
 * The assertion is deliberately about **what survives** rather than about the counts.
 * A count can be right while the survivors are wrong — deleting everything also deletes
 * nothing incorrectly-surviving — so each test here checks that the departing account's
 * child rows are gone AND that another account's are still present. The second half is
 * what makes the first half mean anything.
 *
 * See ADR 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.
 */
@Tag("fast")
class OwnerScopedEraserTest {

    private val owner = UserId("u1")
    private val other = UserId("u2")

    private fun profile(id: String, ownerId: String) =
        ProfileEntity(
            id = id,
            name = id,
            emoji = "🏠",
            colorIdx = 0,
            isDefault = false,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            userId = ownerId,
        )

    private fun task(id: String, userId: String) = TaskEntity(
        id = id,
        userId = userId,
        title = id,
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun note(id: String, userId: String) = NoteEntity(
        id = id,
        userId = userId,
        title = id,
        bodyMarkdown = null,
        bodyHtml = null,
        parentNoteId = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        deletedAt = null,
        archivedAt = null,
    )

    private fun checklist(id: String, taskId: String) = ChecklistItemEntity(
        id = id,
        taskId = taskId,
        title = id,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun project(id: String, userId: String) = ProjectEntity(
        id = id,
        userId = userId,
        name = id,
        color = 0,
        icon = null,
        description = null,
        dueDate = null,
        team = null,
        deletedAt = null,
        parentId = null,
        externalId = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun db() = FakeAppDatabase()

    private suspend fun OwnerScopedEraser.eraseOwner(db: FakeAppDatabase, owner: UserId): EraseCounts =
        erase(OwnerRowIdResolver(db).resolve(owner))

    @Test
    fun `the owner's tasks are gone`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("t1", owner.value)))

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(1, counts.tasks)
        assertTrue(db.taskDao().watchActive(owner.value).first().isEmpty())
    }

    @Test
    fun `another account's tasks survive the erase`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("mine", owner.value), task("theirs", other.value)))

        OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(
            listOf("theirs"),
            db.taskDao().watchActive(other.value).first().map { it.id },
        )
    }

    /**
     * The orphan case: `task_tags` has no `user_id` and no foreign key, so removing the
     * task first would strand the row.
     */
    @Test
    fun `tag references do not outlive the task they point at`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("t1", owner.value), task("t2", other.value)))
        db.seedTagRefs(
            listOf(
                TaskTagCrossRef(taskId = "t1", tagId = "tag1"),
                TaskTagCrossRef(taskId = "t2", tagId = "tag2"),
            ),
        )

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(1, counts.tagRefs, "the owner's tag reference survived its task")
        val left = db.taskDao().getTagIdsForTask("t1").first()
        assertTrue(left.isEmpty(), "a tag reference outlived the task that named it")
    }

    @Test
    fun `another account's tag reference survives`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("t1", owner.value), task("t2", other.value)))
        db.seedTagRefs(
            listOf(
                TaskTagCrossRef(taskId = "t1", tagId = "tag1"),
                TaskTagCrossRef(taskId = "t2", tagId = "tag2"),
            ),
        )

        OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(listOf("tag2"), db.taskDao().getTagIdsForTask("t2").first())
    }

    @Test
    fun `checklist items do not outlive their task`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("t1", owner.value), task("t2", other.value)))
        db.seedChecklist(listOf(checklist("c1", "t1"), checklist("c2", "t2")))

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(1, counts.checklists)
        assertTrue(
            db.checklistDao().watchByTask("t1").first().isEmpty(),
            "a checklist item outlived the task it hung off",
        )
        assertEquals(1, db.checklistDao().watchByTask("t2").first().size)
    }

    @Test
    fun `the owner's notes are gone and another's are not`() = runTest {
        val db = db()
        db.seedNotes(listOf(note("n1", owner.value), note("n2", other.value)))

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(1, counts.notes)
        assertTrue(db.noteDao().watchAll(owner.value).first().isEmpty())
        assertEquals(1, db.noteDao().watchAll(other.value).first().size)
    }

    @Test
    fun `the owner's projects are gone and another's are not`() = runTest {
        val db = db()
        db.seedProjects(listOf(project("p1", owner.value), project("p2", other.value)))

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertEquals(1, counts.projects)
        assertTrue(db.projectDao().watchAll(owner.value).first().isEmpty())
        assertEquals(1, db.projectDao().watchAll(other.value).first().size)
    }

    @Test
    fun `an owner with no rows erases nothing and says so`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("theirs", other.value)))

        val counts = OwnerScopedEraser(db).eraseOwner(db, owner)

        assertTrue(counts.isEmpty, "an empty erase reported removing rows")
        assertEquals(1, db.taskDao().watchActive(other.value).first().size)
    }

    @Test
    fun `an erase with no resolved scope is refused`() = runTest {
        val db = db()
        db.seedTasks(listOf(task("t1", owner.value)))

        val failure = runCatching {
            OwnerScopedEraser(db).erase(OwnerRowIds(owner.value, emptyList(), emptyList()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "an unscoped erase was allowed through")
        assertEquals(1, db.taskDao().watchActive(owner.value).first().size)
    }
}
