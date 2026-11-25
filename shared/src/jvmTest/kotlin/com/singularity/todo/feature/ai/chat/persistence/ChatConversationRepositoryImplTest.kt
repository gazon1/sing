@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat.persistence

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transcript as Room actually stores it.
 *
 * `ChatTranscriptPersistenceTest` proves the ViewModel's behaviour against a fake repository,
 * which cannot prove ordering, ownership or that the schema carries the rows it is asked to
 * carry — the fake decides all three for itself. This test drives the real
 * [ChatConversationRepositoryImpl] over the real schema, so the DAO's `ORDER BY`, its
 * `user_id` scoping and the `position` index are what is under test.
 *
 * ## What a "process restart" is here
 *
 * The fixture's file is closed and opened again through a second [AppDatabase] over the same
 * path: same rows, new instance, nothing in memory. That is the closest a test gets to the
 * process death the issue names, and it is the property that matters — the transcript is on
 * disk, not in a ViewModel.
 */
@Tag("slow")
@Tag("slow:data")
class ChatConversationRepositoryImplTest {
    private lateinit var fixture: DatabaseTestFixture
    private lateinit var db: AppDatabase

    private val owner = UserId("u1")

    @BeforeEach
    fun setUp() {
        fixture = DatabaseTestFixture.onDisk()
        db = fixture.database()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    private fun repository(database: AppDatabase = db): ChatConversationRepositoryImpl =
        ChatConversationRepositoryImpl(
            conversationDao = database.chatConversationDao(),
            messageDao = database.chatMessageDao(),
            currentUser = FakeProfileAwareCurrentUser(initialUserId = owner),
        )

    private fun conversation(
        id: String = "c1",
        updatedAt: Long = 10L,
    ): ChatConversation =
        ChatConversation(
            id = ChatConversationId.fromString(id),
            userId = owner.value,
            createdAt = 1L,
            updatedAt = updatedAt,
        )

    private fun message(
        id: String,
        position: Int,
        content: String = "text",
        role: PersistedChatRole = PersistedChatRole.User,
        status: ChatMessageStatus = ChatMessageStatus.Completed,
        conversationId: ChatConversationId = ChatConversationId.fromString("c1"),
    ): ChatMessageRecord =
        ChatMessageRecord(
            id = ChatMessageId.fromString(id),
            conversationId = conversationId,
            userId = owner.value,
            role = role,
            content = content,
            status = status,
            position = position,
            createdAt = position.toLong(),
            updatedAt = position.toLong(),
        )

    @Test
    fun `messages are read back in position order, not insertion order`() =
        runTest {
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            // Inserted out of order on purpose: the schema's index decides the order, not luck.
            repository.insertMessage(message("m3", position = 2)).getOrThrow()
            repository.insertMessage(message("m1", position = 0)).getOrThrow()
            repository.insertMessage(message("m2", position = 1)).getOrThrow()

            val ids = repository.getMessages(ChatConversationId.fromString("c1")).map { it.id.value }

            assertEquals(listOf("m1", "m2", "m3"), ids)
        }

    @Test
    fun `the transcript survives reopening the database`() =
        runTest {
            repository().createConversation(conversation()).getOrThrow()
            repository().insertMessage(message("m1", position = 0, content = "hello")).getOrThrow()
            repository()
                .insertMessage(
                    message("m2", position = 1, content = "there", role = PersistedChatRole.Assistant),
                ).getOrThrow()

            // Close the database and open the same file again: what a process restart leaves behind.
            db.close()
            // Same fixture, same file, a new database instance: the rows have to come off the
            // disk the first session wrote, not out of anything the test still holds.
            db = fixture.reopen()

            val reopened = repository(db)
            val records = reopened.getMessages(ChatConversationId.fromString("c1"))

            assertEquals(listOf("hello", "there"), records.map { it.content })
            assertEquals(
                "c1",
                reopened
                    .observeLatestConversation()
                    .first()
                    ?.id
                    ?.value,
            )
        }

    @Test
    fun `reconciling marks every in-progress answer interrupted and keeps its text`() =
        runTest {
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            repository
                .insertMessage(
                    message("m1", position = 0, content = "half an ans", status = ChatMessageStatus.InProgress),
                ).getOrThrow()
            repository.insertMessage(message("m2", position = 1, content = "done")).getOrThrow()

            val changed = repository.markInProgressInterrupted(ChatConversationId.fromString("c1"), 99L).getOrThrow()

            assertEquals(1, changed, "only the in-progress row is reconciled")
            val records = repository.getMessages(ChatConversationId.fromString("c1"))
            assertEquals(ChatMessageStatus.Interrupted, records[0].status)
            assertEquals("half an ans", records[0].content, "the partial text must survive reconciliation")
            assertEquals(ChatMessageStatus.Completed, records[1].status, "a completed row is not touched")
        }

    @Test
    fun `a message update cannot reach another user's conversation`() =
        runTest {
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            repository.insertMessage(message("m1", position = 0, content = "mine")).getOrThrow()

            val result =
                repository.updateMessageContent(
                    messageId = ChatMessageId.fromString("m1"),
                    conversationId = ChatConversationId.fromString("other"),
                    content = "theirs",
                    status = ChatMessageStatus.Completed,
                    updatedAt = 5L,
                )

            assertTrue(result.isFailure, "a scoped update must not cross conversations")
            assertEquals("mine", repository.getMessages(ChatConversationId.fromString("c1")).single().content)
        }

    @Test
    fun `the latest conversation is the most recently touched one`() =
        runTest {
            val repository = repository()
            repository.createConversation(conversation(id = "older", updatedAt = 10L)).getOrThrow()
            repository.createConversation(conversation(id = "newer", updatedAt = 20L)).getOrThrow()

            assertEquals(
                "newer",
                repository
                    .observeLatestConversation()
                    .first()
                    ?.id
                    ?.value,
            )

            repository.touchConversation(ChatConversationId.fromString("older"), 30L).getOrThrow()

            assertEquals(
                "older",
                repository
                    .observeLatestConversation()
                    .first()
                    ?.id
                    ?.value,
            )
        }

    private companion object
}
