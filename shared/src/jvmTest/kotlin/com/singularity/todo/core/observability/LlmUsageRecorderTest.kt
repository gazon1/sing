@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.observability

import com.singularity.todo.test.fakes.FakeAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * Tests for [RoomUsageRecorder] — verifies that AI tool call events are
 * correctly persisted and observed through the DAO.
 */
@Tag("fast")
class LlmUsageRecorderTest {

    private fun makeRecorder(): RoomUsageRecorder {
        val db = FakeAppDatabase()
        return RoomUsageRecorder(db.llmUsageDao(), Clock.System)
    }

    @Test
    fun `record persists a successful tool call event`() = runTest {
        val recorder = makeRecorder()

        recorder.record(
            ToolUsageEvent(
                toolName = "refine_task",
                modelId = "gpt-4o-mini",
                inputTokens = 12,
                outputTokens = 8,
                totalTokens = 20,
                costUsdMicros = 600L,
                durationMs = 450L,
                profileId = "user-1",
                error = null,
                timestamp = Clock.System.now(),
            ),
        )

        val events = recorder.observeRecent("user-1").first()
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("refine_task", event.toolName)
        assertEquals("gpt-4o-mini", event.modelId)
        assertEquals(12, event.inputTokens)
        assertEquals(8, event.outputTokens)
        assertEquals(20, event.totalTokens)
        assertEquals(600L, event.costUsdMicros)
        assertEquals(450L, event.durationMs)
        assertNull(event.error)
    }

    @Test
    fun `record persists a failed tool call event with error message`() = runTest {
        val recorder = makeRecorder()

        recorder.record(
            ToolUsageEvent(
                toolName = "decompose_task",
                modelId = "gpt-4o-mini",
                inputTokens = 15,
                outputTokens = 0,
                totalTokens = 15,
                costUsdMicros = 450L,
                durationMs = 300L,
                profileId = "user-1",
                error = "network timeout",
                timestamp = Clock.System.now(),
            ),
        )

        val events = recorder.observeRecent("user-1").first()
        assertEquals(1, events.size)
        assertEquals("network timeout", events.first().error)
    }

    @Test
    fun `observeByTool aggregates tokens across calls`() = runTest {
        val recorder = makeRecorder()

        repeat(3) {
            recorder.record(
                ToolUsageEvent(
                    toolName = "cluster_tasks",
                    modelId = "gpt-4o-mini",
                    inputTokens = 20,
                    outputTokens = 10,
                    totalTokens = 30,
                    costUsdMicros = 600L,
                    durationMs = 300L,
                    profileId = "user-1",
                    error = null,
                    timestamp = Clock.System.now(),
                ),
            )
        }

        val byTool = recorder.observeByTool("user-1").first()
        val clusterTool = byTool.find { it.toolName == "cluster_tasks" }
        assertEquals(90L, clusterTool?.totalTokens) // 3 × 30
        assertEquals(3L, clusterTool?.callCount)
    }

    @Test
    fun `observeRecent returns only events for the specified profile`() = runTest {
        val recorder = makeRecorder()

        recorder.record(
            ToolUsageEvent(
                toolName = "refine_task",
                modelId = "gpt-4o-mini",
                inputTokens = 10,
                outputTokens = 5,
                totalTokens = 15,
                costUsdMicros = 300L,
                durationMs = 200L,
                profileId = "profile-a",
                error = null,
                timestamp = Clock.System.now(),
            ),
        )
        recorder.record(
            ToolUsageEvent(
                toolName = "smart_rewrite",
                modelId = "gpt-4o-mini",
                inputTokens = 8,
                outputTokens = 4,
                totalTokens = 12,
                costUsdMicros = 240L,
                durationMs = 180L,
                profileId = "profile-b",
                error = null,
                timestamp = Clock.System.now(),
            ),
        )

        val eventsForA = recorder.observeRecent("profile-a").first()
        assertEquals(1, eventsForA.size)
        assertEquals("refine_task", eventsForA.first().toolName)

        val eventsForB = recorder.observeRecent("profile-b").first()
        assertEquals(1, eventsForB.size)
        assertEquals("smart_rewrite", eventsForB.first().toolName)
    }

    @Test
    fun `prune keeps events within retention window`() = runTest {
        val recorder = makeRecorder()

        recorder.record(
            ToolUsageEvent(
                toolName = "refine_task",
                modelId = "gpt-4o-mini",
                inputTokens = 10,
                outputTokens = 5,
                totalTokens = 15,
                costUsdMicros = 300L,
                durationMs = 200L,
                profileId = "user-1",
                error = null,
                timestamp = Clock.System.now(),
            ),
        )

        assertEquals(1, recorder.observeRecent("user-1").first().size)

        // Prune anything older than 1 day — the just-recorded event should NOT be removed
        recorder.prune(olderThanDays = 1)

        assertEquals(1, recorder.observeRecent("user-1").first().size)
    }
}
