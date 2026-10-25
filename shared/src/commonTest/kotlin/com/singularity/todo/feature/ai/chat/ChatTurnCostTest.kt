package com.singularity.todo.feature.ai.chat

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.core.observability.RoomUsageRecorder
import com.singularity.todo.core.observability.ToolUsageEvent
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * What one turn of the chat costs the ledger.
 *
 * The guarantee under test (REQ-CHTURN-007): the decorator that wraps the port writes
 * **one row per model call** — a correction is a further call and so a further row — and
 * **no row** for the screen itself. Two writers for one fact is a disagreement waiting to
 * happen, and a call that failed is still a call that was made: the ledger must hold it
 * exactly once, not twice.
 *
 * The fake database backs the recorder, so the rows asserted here are the rows a real
 * Room ledger would hold — same `LlmUsageDao`, same mapping, no SQLite. The profile the
 * rows are filed under is [TestUsers.DEFAULT], because the decorator writes under
 * `currentUser.scopedUserId`, which is that user for a fresh fake.
 */
@Tag("fast")
class ChatTurnCostTest {

    private val clock = Clock.System
    private val profileId: String = TestUsers.DEFAULT.value

    /** One call, one row — even when the call ends in failure. */
    @Test
    fun aFailedStreamIsRecordedOnce() = runTest {
        val db = FakeAppDatabase()

        // The failure reaches the collector; the ledger is written on the way out.
        runCatching {
            decorator(FailingTextGen(), db, backgroundScope).streamChat("hello", instructions = "").toList()
        }

        val rows = rows(db)
        assertEquals(1, rows.size, "One call is one row, failure or not: $rows")
        assertEquals("chat_stream", rows.single().toolName)
        assertTrue(rows.single().error != null, "And the row says the call failed: ${rows.single()}")
    }

    /** A stream that ends normally is one row, too. */
    @Test
    fun aCompletedStreamIsRecordedOnce() = runTest {
        val db = FakeAppDatabase()

        decorator(
            AnsweringTextGen(flowOf(AgentEvent.TextDelta("hi"), AgentEvent.TurnCompleted)),
            db,
            backgroundScope,
        ).streamChat("hello", instructions = "").toList()

        val rows = rows(db)
        assertEquals(1, rows.size, "One call, one row: $rows")
        assertTrue(rows.single().error == null, "And it succeeded: ${rows.single()}")
    }

    private fun decorator(
        delegate: TextGenPort,
        db: FakeAppDatabase,
        scope: CoroutineScope,
    ): UsageRecordingTextGen =
        UsageRecordingTextGen(
            delegate = delegate,
            usageRecorder = RoomUsageRecorder(db.llmUsageDao(), clock),
            currentUser = FakeProfileAwareCurrentUser(
                authRepository = FakeAuthRepository(),
                scope = scope,
            ),
            clock = clock,
        )

    /** The rows the ledger holds for the profile the decorator files under. */
    private suspend fun rows(db: FakeAppDatabase): List<ToolUsageEvent> =
        RoomUsageRecorder(db.llmUsageDao(), clock).observeRecent(profileId).first()
}

/** A model whose stream fails: the failure is what the ledger must still hold once. */
private class FailingTextGen : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.TextDelta("half an ans"))
        error("the provider fell over")
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        Result.success(emptyList())
}

/** A model that answers and finishes. */
private class AnsweringTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        Result.success(emptyList())
}
