@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `OwnerReassignDao` against real SQLite — the one place a wrong ownership transfer is
 * irreversible and invisible.
 *
 * A wrong transfer does not fail loudly. The account signs in, sees its own (empty)
 * data, and the tasks the user made offline are simply not there — filed under an owner
 * that no longer holds a session. So these tests are written to be hostile to the two
 * mistakes an implementation of this DAO can plausibly make:
 *
 * - **Matching by pattern instead of by equality.** An owner named `eu1` and a profile
 *   named `u1` compose to scopes a suffix match cannot tell apart, so a user whose id
 *   merely *contains* another's must survive untouched.
 * - **Forgetting the queue.** A row created offline sits in the outbox under the local
 *   owner; leaving it there makes the claimed task a document the server never hears
 *   about, with no error on either side.
 */
@Tag("slow")
class OwnerReassignDaoTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private val dao: OwnerReassignDao get() = db.ownerReassignDao()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-reassign-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a task moves to exactly the scope it was paired with`() = runTest {
        seedTasks(listOf(task("t1", owner = "local:abc")))
        dao.reassignProfiles("local:abc", "acct-1")
        dao.reassignTasks("local:abc", "acct-1")

        assertEquals("acct-1", db.taskDao().getById("t1")?.userId)
    }

    @Test
    fun `a profile-scoped task keeps its profile and changes only its owner`() = runTest {
        seedTasks(listOf(task("t1", owner = "prof-b/local:abc")))
        dao.reassignProfiles("local:abc", "acct-1")
        dao.reassignTasks("prof-b/local:abc", "prof-b/acct-1")

        val moved = db.taskDao().getById("t1")
        assertEquals("prof-b/acct-1", moved?.userId)
        assertEquals(
            "prof-b",
            moved?.userId?.substringBefore("/"),
            "the profile half of the scope is not part of what changes hands",
        )
    }

    @Test
    fun `an unrelated owner whose id appears inside a profile scope is untouched`() = runTest {
        // `eu1` is an owner of its own; `prof-eu1` is a *profile* of somebody else. A
        // substring or suffix match conflates them, and the loser is a user who did
        // nothing and loses their tasks anyway. See ADR 2026-10-06.
        seedTasks(
            listOf(
                task("victim", owner = "prof-eu1"),
                task("owner", owner = "eu1"),
            ),
        )
        dao.reassignTasks("eu1", "acct-1")

        assertEquals("prof-eu1", db.taskDao().getById("victim")?.userId, "a profile scope that merely contains the id")
        assertEquals("acct-1", db.taskDao().getById("owner")?.userId)
    }

    @Test
    fun `queued patches move with the rows they describe`() = runTest {
        db.syncOutboxDao().insert(patch("p1", "local:abc"))
        db.syncOutboxDao().insert(patch("p2", "somebody-else"))

        val moved = dao.reassignOutbox("local:abc", "acct-1")

        assertEquals(1, moved)
        assertEquals("acct-1", ownerOf("p1", under = "acct-1"), "the claimed patch belongs to the account")
        assertEquals(
            "somebody-else",
            ownerOf("p2", under = "somebody-else"),
            "another account's queued work is not this operation's to move",
        )
    }

    @Test
    fun `a second pass over the same owner moves nothing`() = runTest {
        db.syncOutboxDao().insert(patch("p1", "local:abc"))
        dao.reassignOutbox("local:abc", "acct-1")

        assertEquals(0, dao.reassignOutbox("local:abc", "acct-1"), "the row is no longer under the old owner")
    }

    @Test
    fun `counts report only the scopes asked about`() = runTest {
        seedTasks(
            listOf(
                task("t1", owner = "local:abc"),
                task("t2", owner = "prof-b/local:abc"),
                task("t3", owner = "acct-9"),
            ),
        )

        assertEquals(2, db.ownerScopeDao().countTasks(listOf("local:abc", "prof-b/local:abc")))
        assertEquals(0, db.ownerScopeDao().countTasks(listOf("prof-b/acct-1")))
    }

    /**
     * The owner [patchId] is filed under, read through the queue's own scoped query.
     *
     * The owner is an argument rather than a constant, which is the point: `getPending`
     * filters by owner, so looking up one account's row under another owner returns
     * nothing at all — the row has to be read *as* the owner it is supposed to belong to
     * for "it moved" and "it did not move" to be distinguishable at all.
     */
    private suspend fun ownerOf(patchId: String, under: String): String =
        db.syncOutboxDao().getPending(now = Long.MAX_VALUE, ownerId = under)
            .single { it.patchId == patchId }.ownerId

    private suspend fun seedTasks(tasks: List<TaskEntity>) {
        tasks.forEach { db.taskDao().upsert(it) }
    }

    private fun patch(patchId: String, owner: String) = SyncOutboxEntity(
        patchId = patchId,
        ownerId = owner,
        entityId = "e-$patchId",
        entityType = "task",
        payload = "{}",
        createdAt = 0L,
    )

    private fun task(id: String, owner: String) = TaskEntity(
        id = id,
        title = "task $id",
        description = null,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        archivedAt = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        userId = owner,
    )
}
