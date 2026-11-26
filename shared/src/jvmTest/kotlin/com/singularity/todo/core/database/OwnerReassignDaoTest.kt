@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.database

import com.singularity.todo.core.sync.SyncOutboxEntity
import com.singularity.todo.feature.ai.chat.data.ChatConversationEntity
import com.singularity.todo.feature.ai.chat.data.ChatMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
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
@Tag("slow:data")
class OwnerReassignDaoTest {
    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase
    private val dao: OwnerReassignDao get() = db.ownerReassignDao()

    @BeforeTest
    fun setUp() {
        fixture = DatabaseTestFixture.inMemory()
        db = fixture.database()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a task moves to exactly the scope it was paired with`() =
        runTest {
            seedTasks(listOf(task("t1", owner = "local:abc")))
            dao.reassignProfiles("local:abc", "acct-1")
            dao.reassignTasks("local:abc", "acct-1")

            assertEquals("acct-1", db.taskDao().getById("t1")?.userId)
        }

    @Test
    fun `a profile-scoped task keeps its profile and changes only its owner`() =
        runTest {
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
    fun `an unrelated owner whose id appears inside a profile scope is untouched`() =
        runTest {
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

            assertEquals(
                "prof-eu1",
                db.taskDao().getById("victim")?.userId,
                "a profile scope that merely contains the id",
            )
            assertEquals("acct-1", db.taskDao().getById("owner")?.userId)
        }

    @Test
    fun `queued patches move with the rows they describe`() =
        runTest {
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
    fun `a second pass over the same owner moves nothing`() =
        runTest {
            db.syncOutboxDao().insert(patch("p1", "local:abc"))
            dao.reassignOutbox("local:abc", "acct-1")

            assertEquals(0, dao.reassignOutbox("local:abc", "acct-1"), "the row is no longer under the old owner")
        }

    @Test
    fun `counts report only the scopes asked about`() =
        runTest {
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

    @Test
    fun `chat conversations and messages move to the paired scope and leave others untouched`() =
        runTest {
            val from = "local:abc"
            val to = "acct-1"
            db.chatConversationDao().upsertConversation(conversation("c1", from))
            db.chatConversationDao().upsertConversation(conversation("c2", "somebody-else"))
            db.chatMessageDao().insertMessage(message("m1", "c1", from, position = 0))
            db.chatMessageDao().insertMessage(message("m2", "c1", from, position = 1))
            db.chatMessageDao().insertMessage(message("m3", "c2", "somebody-else", position = 0))

            val movedConversations = dao.reassignChatConversations(from, to)
            val movedMessages = dao.reassignChatMessages(from, to)

            assertEquals(1, movedConversations)
            assertEquals(2, movedMessages)
            assertEquals(to, db.chatConversationDao().getByIdForUser("c1", to)?.userId)
            assertEquals(null, db.chatConversationDao().getByIdForUser("c1", from))
            assertEquals(
                "somebody-else",
                db.chatConversationDao().getByIdForUser("c2", "somebody-else")?.userId,
            )
            assertEquals(2, db.chatMessageDao().listByConversationForUser("c1", to).size)
            assertEquals(0, db.chatMessageDao().listByConversationForUser("c1", from).size)
            assertEquals(1, db.chatMessageDao().listByConversationForUser("c2", "somebody-else").size)
        }

    @Test
    fun `chat conversation and message counts report only the scopes asked about`() =
        runTest {
            val fromA = "local:abc"
            val fromB = "prof-b/local:abc"
            val other = "acct-9"
            db.chatConversationDao().upsertConversation(conversation("c1", fromA))
            db.chatConversationDao().upsertConversation(conversation("c2", fromB))
            db.chatConversationDao().upsertConversation(conversation("c3", other))
            db.chatMessageDao().insertMessage(message("m1", "c1", fromA, position = 0))
            db.chatMessageDao().insertMessage(message("m2", "c2", fromB, position = 0))
            db.chatMessageDao().insertMessage(message("m3", "c3", other, position = 0))

            assertEquals(2, dao.countChatConversations(listOf(fromA, fromB)))
            assertEquals(2, dao.countChatMessages(listOf(fromA, fromB)))
            assertEquals(1, dao.countChatConversations(listOf(other, "missing")))
            assertEquals(1, dao.countChatMessages(listOf(other)))
        }

    /**
     * The owner [patchId] is filed under, read through the queue's own scoped query.
     *
     * The owner is an argument rather than a constant, which is the point: `getPending`
     * filters by owner, so looking up one account's row under another owner returns
     * nothing at all — the row has to be read *as* the owner it is supposed to belong to
     * for "it moved" and "it did not move" to be distinguishable at all.
     */
    private suspend fun ownerOf(
        patchId: String,
        under: String,
    ): String =
        db
            .syncOutboxDao()
            .getPending(now = Long.MAX_VALUE, ownerId = under, profileId = "")
            .single { it.patchId == patchId }
            .ownerId

    private suspend fun seedTasks(tasks: List<TaskEntity>) {
        tasks.forEach { db.taskDao().upsert(it) }
    }

    private fun patch(
        patchId: String,
        owner: String,
    ) = SyncOutboxEntity(
        patchId = patchId,
        ownerId = owner,
        entityId = "e-$patchId",
        entityType = "task",
        payload = "{}",
        createdAt = 0L,
    )

    private fun task(
        id: String,
        owner: String,
    ) = TaskEntity(
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

    private fun conversation(
        id: String,
        owner: String,
        updatedAt: Long = 0L,
    ) = ChatConversationEntity(
        id = id,
        userId = owner,
        createdAt = 0L,
        updatedAt = updatedAt,
    )

    private fun message(
        id: String,
        conversationId: String,
        owner: String,
        position: Int = 0,
    ) = ChatMessageEntity(
        id = id,
        conversationId = conversationId,
        userId = owner,
        role = "User",
        content = "message $id",
        status = "Completed",
        position = position,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
