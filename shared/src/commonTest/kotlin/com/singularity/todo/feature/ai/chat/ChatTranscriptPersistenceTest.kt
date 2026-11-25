@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.ai.chat.persistence.ChatConversation
import com.singularity.todo.feature.ai.chat.persistence.ChatConversationId
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageId
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageRecord
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageStatus
import com.singularity.todo.feature.ai.chat.persistence.PersistedChatRole
import com.singularity.todo.test.fakes.FakeChatConversationRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.SequenceIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ChatTranscriptPersistenceTest {
    private val log = Logger.withTag("ChatTranscriptPersistenceTest")

    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        repo: FakeChatConversationRepository,
        agent: TextGenPort,
        clock: Clock = FixedClock(Instant.parse("2026-11-25T10:00:00Z")),
    ) = ChatViewModel(
        log = log,
        agent = agent,
        idGen = SequenceIdGenerator(),
        conversationRepository = repo,
        unitOfWork = FakeUnitOfWork(),
        clock = clock,
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    @Test
    fun `restores latest conversation and marks in-progress assistant interrupted`() =
        runTest {
            val conversation = ChatConversation(ChatConversationId.fromString("c1"), "u1", 1L, 2L)
            val repo =
                FakeChatConversationRepository(
                    initialConversations = listOf(conversation),
                    initialMessages =
                        listOf(
                            ChatMessageRecord(
                                id = ChatMessageId.fromString("m1"),
                                conversationId = conversation.id,
                                userId = "u1",
                                role = PersistedChatRole.User,
                                content = "hello",
                                status = ChatMessageStatus.Completed,
                                position = 0,
                                createdAt = 1L,
                                updatedAt = 1L,
                            ),
                            ChatMessageRecord(
                                id = ChatMessageId.fromString("m2"),
                                conversationId = conversation.id,
                                userId = "u1",
                                role = PersistedChatRole.Assistant,
                                content = "partial",
                                status = ChatMessageStatus.InProgress,
                                position = 1,
                                createdAt = 2L,
                                updatedAt = 2L,
                            ),
                        ),
                )

            val vm = vm(this, repo, ScriptedTextGen(flowOf(AgentEvent.TurnCompleted)))
            advanceUntilIdle()

            val messages = vm.stateFlow.value.messages
            assertEquals(2, messages.size)
            assertEquals("hello", messages[0].content)
            assertEquals("partial", messages[1].content)
            assertTrue(messages[1].interrupted)
            assertTrue(repo.updatedMessages.any { it.id.value == "m2" && it.status == ChatMessageStatus.Interrupted })
            assertFalse(vm.stateFlow.value.isLoading)
        }

    @Test
    fun `persists user before generation and completes assistant after stream`() =
        runTest {
            val repo = FakeChatConversationRepository()
            val vm =
                vm(
                    this,
                    repo,
                    ScriptedTextGen(
                        flowOf(
                            AgentEvent.TextDelta("Hi "),
                            AgentEvent.TextDelta("there"),
                            AgentEvent.TurnCompleted,
                        ),
                    ),
                )

            vm.onIntent(ChatViewModel.Intent.InputChanged("question"))
            vm.onIntent(ChatViewModel.Intent.Send)
            advanceUntilIdle()

            val records = repo.insertedMessages
            assertEquals(PersistedChatRole.User, records[0].role)
            assertEquals("question", records[0].content)
            assertEquals(PersistedChatRole.Assistant, records[1].role)
            assertTrue(records[0].position < records[1].position)
            assertTrue(
                repo.updatedMessages.any {
                    it.role == PersistedChatRole.Assistant &&
                        it.status == ChatMessageStatus.Completed &&
                        it.content == "Hi there"
                },
            )
            assertIs<AgentTurnOutcome.Completed>(vm.stateFlow.value.turnOutcome)
        }

    @Test
    fun `batches stream persistence instead of writing every token`() =
        runTest {
            val repo = FakeChatConversationRepository()
            val clock =
                object : Clock {
                    var nowMs = 0L

                    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
                }
            val agent =
                object : TextGenPort {
                    override suspend fun generate(
                        prompt: String,
                        systemPrompt: String?,
                        model: String?,
                    ): Result<String> = Result.success("unused")

                    override fun streamChat(
                        message: String,
                        instructions: String,
                    ): Flow<AgentEvent> =
                        flow {
                            emit(AgentEvent.TextDelta("a"))
                            clock.nowMs += 10
                            emit(AgentEvent.TextDelta("b"))
                            clock.nowMs += 10
                            emit(AgentEvent.TextDelta("c"))
                            clock.nowMs += 300
                            emit(AgentEvent.TextDelta("d"))
                            emit(AgentEvent.TurnCompleted)
                        }

                    override suspend fun listModels(
                        baseUrl: String,
                        apiKey: String,
                    ): Result<List<String>> = Result.success(emptyList())
                }
            val vm = vm(this, repo, agent, clock)

            vm.onIntent(ChatViewModel.Intent.InputChanged("q"))
            vm.onIntent(ChatViewModel.Intent.Send)
            advanceUntilIdle()

            val inProgressUpdates = repo.updatedMessages.filter { it.status == ChatMessageStatus.InProgress }
            assertTrue(
                inProgressUpdates.size < 4,
                "Expected batched stream persistence, got ${inProgressUpdates.size} updates",
            )
            assertTrue(repo.updatedMessages.any { it.status == ChatMessageStatus.Completed && it.content == "abcd" })
        }

    @Test
    fun `cancelled turn keeps partial content and marks assistant interrupted`() =
        runTest {
            val repo = FakeChatConversationRepository()
            val agent = SuspendingTextGen()
            val vm = vm(this, repo, agent)

            vm.onIntent(ChatViewModel.Intent.InputChanged("q"))
            vm.onIntent(ChatViewModel.Intent.Send)
            agent.started.await()
            vm.onIntent(ChatViewModel.Intent.Stop)
            advanceUntilIdle()

            val assistant =
                vm.stateFlow.value.messages
                    .last()
            assertEquals("partial", assistant.content)
            assertTrue(assistant.interrupted)
            assertIs<AgentTurnOutcome.Cancelled>(vm.stateFlow.value.turnOutcome)
            assertTrue(
                repo.updatedMessages.any { it.status == ChatMessageStatus.Interrupted && it.content == "partial" },
            )
        }

    @Test
    fun `restoration that cannot reconcile shows no transcript at all`() =
        runTest {
            // An unreconciled row would be drawn as an answer that is still running, so the
            // restore fails instead of showing it. The screen then holds no conversation,
            // which is true, rather than one that claims execution survived the process.
            val conversation = ChatConversation(ChatConversationId.fromString("c1"), "u1", 1L, 2L)
            val repo =
                FakeChatConversationRepository(
                    initialConversations = listOf(conversation),
                    initialMessages =
                        listOf(
                            ChatMessageRecord(
                                id = ChatMessageId.fromString("m1"),
                                conversationId = conversation.id,
                                userId = "u1",
                                role = PersistedChatRole.Assistant,
                                content = "partial",
                                status = ChatMessageStatus.InProgress,
                                position = 0,
                                createdAt = 1L,
                                updatedAt = 1L,
                            ),
                        ),
                    failReconciliation = true,
                )

            val vm = vm(this, repo, ScriptedTextGen(flowOf(AgentEvent.TurnCompleted)))
            advanceUntilIdle()

            assertTrue(
                vm.stateFlow.value.messages
                    .isEmpty(),
                "a transcript that cannot be reconciled must not be shown",
            )
        }

    private class ScriptedTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
        override suspend fun generate(
            prompt: String,
            systemPrompt: String?,
            model: String?,
        ): Result<String> = Result.success("unused")

        override fun streamChat(
            message: String,
            instructions: String,
        ): Flow<AgentEvent> = scripted

        override suspend fun listModels(
            baseUrl: String,
            apiKey: String,
        ): Result<List<String>> = Result.success(emptyList())
    }

    private class SuspendingTextGen : TextGenPort {
        val started = CompletableDeferred<Unit>()

        override suspend fun generate(
            prompt: String,
            systemPrompt: String?,
            model: String?,
        ): Result<String> = Result.success("unused")

        override fun streamChat(
            message: String,
            instructions: String,
        ): Flow<AgentEvent> =
            flow {
                emit(AgentEvent.TextDelta("partial"))
                started.complete(Unit)
                awaitCancellation()
            }

        override suspend fun listModels(
            baseUrl: String,
            apiKey: String,
        ): Result<List<String>> = Result.success(emptyList())
    }

    private class FixedClock(private val fixedNow: Instant) : Clock {
        override fun now(): Instant = fixedNow
    }
}
