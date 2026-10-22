package com.singularity.todo.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat turn, tested through the questions the turn has to answer honestly.
 *
 * The loop is the part of the chat that decides whether the model can reach the user's data
 * at all. Before it existed, the chat screen streamed text and the model's answer to
 * "what are my overdue tasks?" was a guess. These tests pin the three things that make it
 * more than a guess: the tool is really called, its answer really reaches the model, and a
 * model that keeps calling tools cannot hold the conversation open.
 */
@Tag("fast")
class ToolCallLoopTest {

    @Serializable
    data class EchoInput(val value: String = "")

    /**
     * A tool that says what it was told.
     *
     * It records its calls, because "the tool ran" is a claim the test has to be able to
     * check rather than infer from the conversation.
     */
    class EchoTool :
        SimpleTool<EchoInput>(
            TypeToken.of(EchoInput::class.java),
            NAME,
            "Echoes the value back.",
        ) {
        val calls = mutableListOf<EchoInput>()

        override suspend fun execute(args: EchoInput): String {
            calls += args
            return "echoed:${args.value}"
        }

        companion object {
            const val NAME = "echo"
        }
    }

    /**
     * A model that says what it was scripted to say, one round at a time.
     *
     * It keeps the prompts it was given, so a test can look at what the second round
     * actually saw — which is the only place the tool's answer can be shown to have
     * arrived.
     */
    class ScriptedModel(private val rounds: List<List<StreamFrame>>) {
        val prompts = mutableListOf<Prompt>()
        val offeredTools = mutableListOf<List<ToolDescriptor>>()

        fun stream(prompt: Prompt, descriptors: List<ToolDescriptor>): Flow<StreamFrame> {
            prompts += prompt
            offeredTools += descriptors
            val index = prompts.size - 1
            return flowOf(*rounds.getOrElse(index) { emptyList() }.toTypedArray())
        }
    }

    private fun initialPrompt(): Prompt = prompt(Prompt.Empty, KoogClock.System) {
        system("You are an assistant.")
        user("What did I say?")
    }

    private fun textOf(message: Message): String =
        message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    private fun toolCallsOf(message: Message): List<MessagePart.Tool.Call> =
        message.parts.filterIsInstance<MessagePart.Tool.Call>()

    private fun toolResultsOf(message: Message): List<MessagePart.Tool.Result> =
        message.parts.filterIsInstance<MessagePart.Tool.Result>()

    @Test
    fun `a question the model answers without a tool is answered in one round`() = runTest {
        val model = ScriptedModel(listOf(listOf(StreamFrame.TextDelta("You said hello."))))

        val text = ToolCallLoop(tools = emptyList())
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf("You said hello."), text)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun `the model may call a tool, and the tool really runs`() = runTest {
        val tool = EchoTool()
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamFrame.TextDelta("Let me look. "),
                    StreamFrame.ToolCallComplete(
                        id = "call-1",
                        name = EchoTool.NAME,
                        content = """{"value":"hello"}""",
                    ),
                ),
                listOf(StreamFrame.TextDelta("You said hello.")),
            ),
        )

        val text = ToolCallLoop(tools = listOf(tool))
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf("Let me look. ", "You said hello."), text)
        assertEquals(listOf(EchoInput("hello")), tool.calls)
        assertEquals(2, model.prompts.size)
    }

    @Test
    fun `the tool's answer reaches the model`() = runTest {
        val tool = EchoTool()
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamFrame.ToolCallComplete(
                        id = "call-1",
                        name = EchoTool.NAME,
                        content = """{"value":"hello"}""",
                    ),
                ),
                listOf(StreamFrame.TextDelta("Done.")),
            ),
        )

        ToolCallLoop(tools = listOf(tool))
            .run(initialPrompt(), model::stream)
            .toList()

        val secondRound = model.prompts[1].messages

        // The model must be shown what it asked for and what came back, in the shape the
        // backends expect: an assistant turn that carries the call, and a user turn that
        // carries the outcome.
        val assistant = secondRound.filterIsInstance<Message.Assistant>().single()
        assertEquals(listOf("call-1"), toolCallsOf(assistant).map { it.id })

        val user = secondRound.filterIsInstance<Message.User>().last()
        val results = toolResultsOf(user)
        assertEquals(1, results.size)
        assertEquals("call-1", results.single().id)
        assertEquals("echoed:hello", results.single().output)
    }

    @Test
    fun `a call the model made but no tool answers is an error the model can read`() = runTest {
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamFrame.ToolCallComplete(
                        id = "call-1",
                        name = "no_such_tool",
                        content = "{}",
                    ),
                ),
                listOf(StreamFrame.TextDelta("I could not do that.")),
            ),
        )

        val text = ToolCallLoop(tools = emptyList())
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf("I could not do that."), text)

        val user = model.prompts[1].messages.filterIsInstance<Message.User>().last()
        val result = toolResultsOf(user).single()
        assertTrue(result.isError, "an unknown tool is an error, not a silent success")
        assertTrue(result.output.contains("no_such_tool"), "the error names the tool that was missing")
    }

    @Test
    fun `a tool that fails does not end the conversation`() = runTest {
        val failing = object : SimpleTool<EchoInput>(
            TypeToken.of(EchoInput::class.java),
            "boom",
            "Always fails.",
        ) {
            override suspend fun execute(args: EchoInput): String = throw IllegalStateException("no")
        }
        val model = ScriptedModel(
            listOf(
                listOf(
                    StreamFrame.ToolCallComplete(id = "call-1", name = "boom", content = "{}"),
                ),
                listOf(StreamFrame.TextDelta("That did not work.")),
            ),
        )

        val text = ToolCallLoop(tools = listOf(failing))
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf("That did not work."), text)

        val user = model.prompts[1].messages.filterIsInstance<Message.User>().last()
        val result = toolResultsOf(user).single()
        assertTrue(result.isError, "a failed tool is an error the model can read")
    }

    @Test
    fun `a model that keeps calling tools is stopped, and the last call is still honoured`() = runTest {
        val tool = EchoTool()
        val call = StreamFrame.ToolCallComplete(
            id = "call-1",
            name = EchoTool.NAME,
            content = """{"value":"again"}""",
        )
        val model = ScriptedModel(
            listOf(
                listOf(call),
                listOf(call),
                listOf(StreamFrame.TextDelta("I will answer now.")),
            ),
        )

        val text = ToolCallLoop(tools = listOf(tool), maxRounds = 2)
            .run(initialPrompt(), model::stream)
            .toList()

        // Two rounds of tool calls, then a round with no tools, in which the model answers.
        assertEquals(3, model.prompts.size)
        assertEquals(2, tool.calls.size)
        assertEquals(listOf("I will answer now."), text)

        // The last round offered no tools, so the model had to answer in words.
        assertTrue(model.offeredTools[2].isEmpty(), "the final round offers no tools")
        assertEquals(1, model.offeredTools[0].size, "the first round offers the tool")
    }

    @Test
    fun `a call whose arguments do not fit the tool is told what to look at`() = runTest {
        val tool = EchoTool()
        val model = ScriptedModel(
            listOf(
                listOf(
                    // "value" is a number, and the tool's field is a string.
                    StreamFrame.ToolCallComplete(id = "call-1", name = EchoTool.NAME, content = """{"value":7}"""),
                ),
                listOf(StreamFrame.TextDelta("I will try again.")),
            ),
        )

        val text = ToolCallLoop(tools = listOf(tool))
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf("I will try again."), text)

        val user = model.prompts[1].messages.filterIsInstance<Message.User>().last()
        val result = toolResultsOf(user).single()
        assertTrue(result.isError, "arguments that do not fit are an error")
        assertTrue(
            result.output.contains("arguments"),
            "the model is told that the arguments are what is wrong: ${result.output}",
        )
    }

    @Test
    fun `the tools the model is offered are the ones the loop was given`() = runTest {
        val tool = EchoTool()
        val model = ScriptedModel(listOf(listOf(StreamFrame.TextDelta("hi"))))

        ToolCallLoop(tools = listOf(tool))
            .run(initialPrompt(), model::stream)
            .toList()

        assertEquals(listOf(EchoTool.NAME), model.offeredTools[0].map { it.name })
    }
}
