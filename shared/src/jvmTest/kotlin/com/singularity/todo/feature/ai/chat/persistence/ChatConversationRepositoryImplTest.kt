@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat.persistence

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.DatabaseTestFixture
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.feature.ai.chat.ChatViewModel
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.SequenceIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

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
    fun `a message cannot be filed into a conversation that is not the caller's own`() =
        runTest {
            // Ownership is a property of the conversation, resolved at the write, so a writer
            // holding a conversation handle cannot file a message into one it does not own.
            val repository = repository()

            val result =
                repository.insertMessage(
                    message("m1", position = 0, conversationId = ChatConversationId.fromString("missing")),
                )

            assertTrue(result.isFailure, "a writer must not file a message into a conversation it cannot resolve")
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

    @Test
    fun `a turn whose second row fails leaves neither row behind`() =
        runTest {
            // A turn is two rows and one transaction. If the answer's row cannot be written,
            // the question must not stay: a restored transcript would then show a question
            // nobody answered, which is a turn the reader never took.
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            val writer =
                ChatTranscriptWriter(
                    repository = repository,
                    unitOfWork = RoomUnitOfWork(db),
                    clock = Clock.System,
                    log = Logger.withTag("ChatConversationRepositoryImplTest"),
                )
            // The answer's id already exists, so its primary key collides and the insert fails.
            repository.insertMessage(message("taken", position = 0)).getOrThrow()

            val started =
                writer.beginTurn(
                    question = ChatTranscriptWriter.Question(ChatMessageId.fromString("q1"), "a question"),
                    assistantId = ChatMessageId.fromString("taken"),
                )

            assertTrue(!started, "a turn whose answer cannot be written is not started")
            assertEquals(
                listOf("taken"),
                repository.getMessages(ChatConversationId.fromString("c1")).map { it.id.value },
                "the question must be rolled back with the answer",
            )
        }

    @Test
    fun `a ViewModel over a reopened database restores the conversation it never saw`() =
        runTest {
            // The two halves of the guarantee, joined: the writer that wrote the rows and the
            // reader that shows them are different objects over a different database instance,
            // and the only thing they share is the file.
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            val writer =
                ChatTranscriptWriter(
                    repository = repository,
                    unitOfWork = RoomUnitOfWork(db),
                    clock = Clock.System,
                    log = Logger.withTag("ChatConversationRepositoryImplTest"),
                )
            writer.beginTurn(
                question = ChatTranscriptWriter.Question(ChatMessageId.fromString("q1"), "what is due today?"),
                assistantId = ChatMessageId.fromString("a1"),
            )
            writer.finish("nothing is due today.", ChatMessageStatus.Completed)

            db.close()
            db = fixture.reopen()

            val restored =
                ChatViewModel(
                    log = Logger.withTag("ChatConversationRepositoryImplTest"),
                    agent = SilentTextGen,
                    idGen = SequenceIdGenerator(),
                    surfaces = null,
                    conversationRepository = repository(db),
                    unitOfWork = RoomUnitOfWork(db),
                    clock = Clock.System,
                    scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
                )
            advanceUntilIdle()
            // Room executes the restore's queries on its own dispatcher, off the virtual-time
            // scheduler the test drives, so `advanceUntilIdle` returns before the read lands; the
            // restore writes the state from that thread, so wait for it before asserting.
            val messages = restored.stateFlow.value.messages
            while (messages.isEmpty()) {
                withContext(Dispatchers.Default) { delay(10L) }
            }
            assertEquals(listOf("what is due today?", "nothing is due today."), messages.map { it.content })
            assertTrue(messages.none { it.interrupted }, "a completed turn is not restored as interrupted")
        }

    @Test
    fun `a ViewModel over a reopened database marks the answer it found unfinished interrupted`() =
        runTest {
            val repository = repository()
            repository.createConversation(conversation()).getOrThrow()
            val writer =
                ChatTranscriptWriter(
                    repository = repository,
                    unitOfWork = RoomUnitOfWork(db),
                    clock = Clock.System,
                    log = Logger.withTag("ChatConversationRepositoryImplTest"),
                )
            // Left in progress: the process died mid-answer, which is what the row says.
            writer.beginTurn(
                question = ChatTranscriptWriter.Question(ChatMessageId.fromString("q1"), "a question"),
                assistantId = ChatMessageId.fromString("a1"),
            )
            writer.onDelta("half an ans")

            db.close()
            db = fixture.reopen()

            val restored =
                ChatViewModel(
                    log = Logger.withTag("ChatConversationRepositoryImplTest"),
                    agent = SilentTextGen,
                    idGen = SequenceIdGenerator(),
                    surfaces = null,
                    conversationRepository = repository(db),
                    unitOfWork = RoomUnitOfWork(db),
                    clock = Clock.System,
                    scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
                )
            advanceUntilIdle()
            // Room executes the restore's queries on its own dispatcher, off the virtual-time
            // scheduler the test drives, so `advanceUntilIdle` returns before the read lands; the
            // restore writes the state from that thread, so wait for it before asserting.
            val messages = restored.stateFlow.value.messages
            while (messages.isEmpty()) {
                withContext(Dispatchers.Default) { delay(10L) }
            }

            val answer =
                messages
                    .last()
            assertEquals("half an ans", answer.content, "the partial answer is kept")
            assertTrue(answer.interrupted, "an answer the process did not finish is interrupted")
        }

    /** A model nobody asks: these cases are about what the database says, not what it answers. */
    private object SilentTextGen : com.singularity.todo.feature.ai.TextGenPort {
        override suspend fun generate(
            prompt: String,
            systemPrompt: String?,
            model: String?,
        ): Result<String> = Result.success("")

        override fun streamChat(
            message: String,
            instructions: String,
        ): kotlinx.coroutines.flow.Flow<AgentEvent> = kotlinx.coroutines.flow.emptyFlow()

        override suspend fun listModels(
            baseUrl: String,
            apiKey: String,
        ): Result<List<String>> = Result.success(emptyList())
    }

    private companion object
}
