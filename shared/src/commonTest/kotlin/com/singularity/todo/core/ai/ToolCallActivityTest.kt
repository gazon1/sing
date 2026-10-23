@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.ToolCallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool that answers, and remembers that it ran.
 */
private class AnsweringTool : SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
    var ran: Boolean = false
        private set

    override suspend fun execute(args: Unit): String {
        ran = true
        return """{"ok":true}"""
    }

    companion object {
        const val NAME = "answering_tool"
        const val DESCRIPTION = "A tool that answers."
    }
}

/**
 * A tool that fails, so the failure can be seen.
 */
private class FailingTool : SimpleTool<Unit>(TypeToken.of(Unit::class.java), NAME, DESCRIPTION) {
    override suspend fun execute(args: Unit): String = throw IllegalStateException("the tool broke")

    companion object {
        const val NAME = "failing_tool"
        const val DESCRIPTION = "A tool that fails."
    }
}

/**
 * What the loop shows the reader.
 *
 * The loop used to emit the model's text and nothing else, on the principle that tool calls
 * are the conversation's business. That principle is what this test now pins the other way
 * round: a call that is running, a call that finished, a call that failed, and the end of
 * the turn are all things the reader can see.
 */
@Tag("fast")
class ToolCallActivityTest {

    private fun conversation(): Prompt = prompt(Prompt.Empty, KoogClock.System) {
        system("you are a test")
        user("hello")
    }

    /**
     * The model's rounds, one after the other.
     *
     * The loop asks the model again after every round in which the model called a tool, so
     * a scripted stream has to be scripted per round — a stream that always answered with a
     * tool call would ask for one on every round, and the loop's cap would be the only
     * thing that stopped it.
     */
    private class ScriptedModel(private val rounds: List<List<StreamFrame>>) {
        private var round = 0

        fun next(): Flow<StreamFrame> {
            val frames = rounds.getOrElse(round) { listOf(StreamFrame.TextDelta("done")) }
            round++
            return flowOf(*frames.toTypedArray())
        }
    }

    private fun scripted(vararg rounds: List<StreamFrame>): ScriptedModel = ScriptedModel(rounds.toList())

    @Test
    fun theTextArrivesAsDeltas() = runTest {
        val events = ToolCallLoop(tools = emptyList())
            .run(conversation()) { _, _ ->
                flowOf(StreamFrame.TextDelta("Hello "), StreamFrame.TextDelta("there"))
            }
            .toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello there", text, "The deltas add up to the answer")
    }

    @Test
    fun aTurnEndsWithCompletion() = runTest {
        val events = ToolCallLoop(tools = emptyList())
            .run(conversation()) { _, _ -> flowOf(StreamFrame.TextDelta("done")) }
            .toList()

        assertTrue(
            events.last() is AgentEvent.TurnCompleted,
            "The turn says it finished: ${events.last()}",
        )
    }

    @Test
    fun aToolCallIsAnnouncedBeforeItRuns() = runTest {
        val tool = AnsweringTool()
        val model = scripted(
            listOf(StreamFrame.ToolCallComplete(id = "c1", name = AnsweringTool.NAME, content = "{}")),
            listOf(StreamFrame.TextDelta("done")),
        )

        val events = ToolCallLoop(tools = listOf(tool))
            .run(conversation()) { _, _ -> model.next() }
            .toList()

        val calls = events.filterIsInstance<AgentEvent.ToolCallUpdated>()
        assertEquals(2, calls.size, "The call is announced and then reported: ${calls.size}")
        assertEquals(ToolCallStatus.RUNNING, calls.first().status, "It was running first")
        assertEquals(ToolCallStatus.COMPLETED, calls.last().status, "And then it completed")
        assertTrue(tool.ran, "The tool actually ran")
    }

    @Test
    fun aFailedToolIsVisible() = runTest {
        val model = scripted(
            listOf(StreamFrame.ToolCallComplete(id = "c1", name = FailingTool.NAME, content = "{}")),
            listOf(StreamFrame.TextDelta("done")),
        )

        val events = ToolCallLoop(tools = listOf(FailingTool()))
            .run(conversation()) { _, _ -> model.next() }
            .toList()

        val calls = events.filterIsInstance<AgentEvent.ToolCallUpdated>()
        assertEquals(ToolCallStatus.FAILED, calls.last().status, "The failure is shown, not swallowed")
    }

    @Test
    fun aCallCarriesTheNameTheModelUsed() = runTest {
        val model = scripted(
            listOf(StreamFrame.ToolCallComplete(id = "c1", name = AnsweringTool.NAME, content = "{}")),
            listOf(StreamFrame.TextDelta("done")),
        )

        val events = ToolCallLoop(tools = listOf(AnsweringTool()))
            .run(conversation()) { _, _ -> model.next() }
            .toList()

        val calls = events.filterIsInstance<AgentEvent.ToolCallUpdated>()
        assertEquals(AnsweringTool.NAME, calls.first().name, "The card names the tool the model asked for")
    }

    @Test
    fun aCallWithoutAnIdentifierStillGetsOne() = runTest {
        val model = scripted(
            listOf(StreamFrame.ToolCallComplete(id = null, name = AnsweringTool.NAME, content = "{}")),
            listOf(StreamFrame.TextDelta("done")),
        )

        val events = ToolCallLoop(tools = listOf(AnsweringTool()))
            .run(conversation()) { _, _ -> model.next() }
            .toList()

        val calls = events.filterIsInstance<AgentEvent.ToolCallUpdated>()
        assertTrue(calls.all { it.id.isNotBlank() }, "A card needs a name: ${calls.map { it.id }}")
        assertEquals(1, calls.map { it.id }.distinct().size, "And one card per call")
    }

    @Test
    fun aCallCarriesASummary() = runTest {
        val model = scripted(
            listOf(
                StreamFrame.ToolCallComplete(
                    id = "c1",
                    name = AnsweringTool.NAME,
                    content = """{"id":"t-42"}""",
                ),
            ),
            listOf(StreamFrame.TextDelta("done")),
        )

        val events = ToolCallLoop(tools = listOf(AnsweringTool()))
            .run(conversation()) { _, _ -> model.next() }
            .toList()

        val calls = events.filterIsInstance<AgentEvent.ToolCallUpdated>()
        assertTrue(
            calls.last().summary?.contains("t-42") == true,
            "The summary names what was touched: ${calls.last().summary}",
        )
    }
}
