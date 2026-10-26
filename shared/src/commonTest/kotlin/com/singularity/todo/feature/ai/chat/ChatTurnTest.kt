package com.singularity.todo.feature.ai.chat

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.engine.SurfaceTurn
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The turn, end to end, against a model that fragments its output the way a real streaming
 * provider does.
 *
 * This is the test that replaces the one that drove the session: the turn asks the model once,
 * reads the answer as prose and as a screen, and asks again when the screen was rejected. A
 * fragmented answer is the point — a turn that only worked on whole lines would pass a test
 * written against a model that answers in whole lines, and would fail in the app.
 */
@Tag("fast")
class ChatTurnTest {

    private val controller = SurfaceController()
    private val parser = A2uiParser(SingularityCatalog)
    private val processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller)

    private val surface = SurfaceId("test")

    @Test
    fun aFragmentedResponseProducesASurface() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 9))
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        assertTrue(screen.rejections.isEmpty(), screen.readerFeedback())
        assertEquals(1, agent.requests.size, "A valid answer is not corrected")
        assertNotNull(controller.snapshot(surface), "The surface is on screen")
    }

    @Test
    fun proseBecomesTheReplyAndTheSurfaceIsSeparate() = runTest {
        val response = "Here are your tasks:\n${validSurface()}\nAnything else?"
        val agent = ScriptedTextGen(fragments(response, 11))
        val screen = screen(surface)
        val said = StringBuilder()

        turn(agent, screen).run("show my tasks").collect { event ->
            if (event is AgentEvent.TextDelta) said.append(event.text)
        }

        assertTrue("Here are your tasks:" in said.toString(), said.toString())
        assertTrue("Anything else?" in said.toString(), said.toString())
        assertNotNull(controller.snapshot(surface))
    }

    @Test
    fun theScreenIsNotPrintedToTheReader() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 9))
        val screen = screen(surface)
        val said = StringBuilder()

        turn(agent, screen).run("show my tasks").collect { event ->
            if (event is AgentEvent.TextDelta) said.append(event.text)
        }

        assertFalse("{" in said.toString(), "The reader sees words, not a protocol: $said")
    }

    /**
     * A message spread over several lines reaches the screen, and none of it reaches the
     * reader.
     *
     * A model asked for JSON Lines often pretty-prints instead, and a pretty-printed
     * message is still one message: its first line opens it and a later line closes it.
     * Read line by line, the first line is a truncated object the parser must reject — a
     * rejection the model is then told about for a message it wrote correctly — and the
     * body's own lines, which open with a quote, would arrive at the reader as prose.
     *
     * So the test drives a pretty-printed surface and asserts the two things that must be
     * true: the screen is drawn (the message was assembled, not rejected, hence one call),
     * and no fragment of the message reached the reader.
     */
    @Test
    fun aPrettyPrintedMessageIsOneMessageAndTheReaderNeverSeesIt() = runTest {
        val agent = ScriptedTextGen(fragments(prettyPrintedSurface(), 7))
        val screen = screen(surface)
        val said = StringBuilder()

        turn(agent, screen).run("show my tasks").collect { event ->
            if (event is AgentEvent.TextDelta) said.append(event.text)
        }

        val printed: String = said.toString()
        assertFalse("createSurface" in printed, "The reader sees words, not a protocol: $printed")
        assertFalse("surfaceId" in printed, "Nor the message's own body: $printed")
        assertTrue(screen.rejections.isEmpty(), screen.readerFeedback())
        assertEquals(1, agent.requests.size, "A correctly written message is not corrected")
        assertNotNull(controller.snapshot(surface), "The message was assembled and drawn")
    }

    @Test
    fun aHallucinatedComponentCostsOneExtraCallAndThenSucceeds() = runTest {
        val agent = ScriptedTextGen(answers(badSurface(), validSurface()))
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        assertTrue(screen.rejections.isEmpty(), screen.readerFeedback())
        assertEquals(2, agent.requests.size, "Exactly one correction")
        assertTrue(
            "UNKNOWN_COMPONENT" in agent.requests[1],
            "The model is told what was wrong: ${agent.requests[1]}",
        )
        assertTrue(
            "text" in agent.requests[1],
            "The model is told which components exist: ${agent.requests[1]}",
        )
    }

    @Test
    fun aModelThatNeverConvergesStopsAtTheBoundAndReportsIt() = runTest {
        val agent = ScriptedTextGen(repeating(badSurface(), 5))
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        assertTrue(screen.rejections.isNotEmpty(), "An unbounded loop would cost tokens forever")
        assertTrue(agent.requests.size <= 3, "At most one call plus two corrections: ${agent.requests.size}")
        assertTrue(screen.rejections.any { it.code == A2uiErrorCode.UNKNOWN_COMPONENT })
    }

    @Test
    fun aFailedTurnLeavesNothingHalfApplied() = runTest {
        val agent = ScriptedTextGen(repeating(badSurface(), 5))
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        val snapshot = controller.snapshot(surface)
        // The rejected component was never in a message that applied, so there is nothing to find.
        assertTrue(snapshot == null || snapshot.components.isEmpty(), snapshot?.components.toString())
    }

    @Test
    fun theCatalogInstructionsArePartOfTheCall() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 13))

        turn(agent, screen(surface)).run("show my tasks").collect { }

        assertTrue("Components" in agent.instructionsSeen.first(), "The catalog must reach the model")
        assertTrue("task_card" in agent.instructionsSeen.first(), "The domain components must be offered")
    }

    @Test
    fun theOriginalPromptIsRepeatedSoTheModelKnowsWhatItWasBuilding() = runTest {
        val agent = ScriptedTextGen(answers(badSurface(), validSurface()))

        turn(agent, screen(surface)).run("show my tasks").collect { }

        assertTrue(agent.requests[1].contains("show my tasks"), agent.requests[1])
    }

    // Each answer ends with a newline. A message with no terminator was cut off by the model, and
    // the framer reports that rather than guessing at the rest — so a fixture without one would be
    // testing truncation, not the thing it was written for.
    // ─── C1: a screen belongs to one answer ──────────────────────────────

    @Test
    fun twoAnswersDoNotShareAScreen() = runTest {
        val agent = ScriptedTextGen(repeatedFragments(validSurface(), 9, times = 2))
        val first = SurfaceId("first")
        val second = SurfaceId("second")

        turn(agent, screen(first)).run("show my tasks").collect { }
        turn(agent, screen(second)).run("now only overdue").collect { }

        assertNotNull(controller.snapshot(first), "The first answer keeps its own screen")
        assertNotNull(controller.snapshot(second), "The second answer has its own")
        assertTrue(first != second)
    }

    /**
     * The identifier the model wrote inside the message is not the address the screen ends up at.
     *
     * Both answers here declare `"surfaceId":"test"` — the same name a model reaches for every time.
     * If the wire name won, the second answer would either collide with the first or overwrite it,
     * and the message that asked for the first screen would redraw the second one.
     */
    @Test
    fun theIdentifierTheModelWroteIsNotTheAddressTheScreenEndsUpAt() = runTest {
        val agent = ScriptedTextGen(repeatedFragments(validSurface(), 9, times = 2))
        val first = SurfaceId("first")
        val second = SurfaceId("second")

        turn(agent, screen(first)).run("show my tasks").collect { }
        val firstRoot: UiNode.Column? = controller.snapshot(first)?.components?.get("root") as? UiNode.Column
        turn(agent, screen(second)).run("now only overdue").collect { }

        assertNotNull(controller.snapshot(first), "The first answer still has a screen of its own")
        assertNotNull(controller.snapshot(second), "And so does the second")
        assertEquals(firstRoot, controller.snapshot(first)?.components?.get("root"), "It was not redrawn")
        assertEquals(2, controller.surfaces.value.size, "Both live side by side")
    }

    @Test
    fun anAnswerWithNoScreenLeavesEarlierOnesAlone() = runTest {
        val agent = ScriptedTextGen(
            listOf(listOf(validSurface() + "\n"), listOf("Just a sentence.\n")),
        )
        val first = SurfaceId("first")
        val second = SurfaceId("second")
        val secondScreen = screen(second)

        turn(agent, screen(first)).run("show my tasks").collect { }
        turn(agent, secondScreen).run("what is the weather").collect { }

        assertFalse(secondScreen.drawn, "An answer with no screen draws no screen")
        assertNotNull(controller.snapshot(first), "A prose answer must not empty an earlier screen")
    }

    // ─── C3: the model is told about this attempt only ─────────────────────

    @Test
    fun aCorrectionNamesOnlyTheLatestRejections() = runTest {
        // First answer is wrong, second is wrong in a different way, third is right.
        val agent = ScriptedTextGen(
            listOf(
                listOf(unknownComponentSurface("chart") + "\n"),
                listOf(missingPropertySurface() + "\n"),
                listOf(validSurface() + "\n"),
            ),
        )

        turn(agent, screen(surface)).run("show my tasks").collect { }

        assertEquals(3, agent.requests.size)
        val secondRequest: String = agent.requests[1]
        val thirdRequest: String = agent.requests[2]
        assertTrue("UNKNOWN_COMPONENT" in secondRequest, secondRequest)
        assertTrue("UNKNOWN_COMPONENT" !in thirdRequest, "A fixed error must not be sent back: $thirdRequest")
        assertTrue("MISSING_PROPERTY" in thirdRequest, thirdRequest)
    }

    // ─── C4: a truncated tail is a transport fact, not a contract breach ───

    @Test
    fun aTruncatedTailAfterAUsableScreenIsNotRetried() = runTest {
        // A screen, then a line that never finished arriving. No terminator on the tail: a model
        // cut off by its token limit has not written the newline yet, and a newline there would
        // make this a different failure — an unreadable line, which the model can be corrected on.
        val answer: String = validSurface() + "\n" + """{"updateData":{"surfaceId":"test","path":"/na"""
        val agent = ScriptedTextGen(listOf(answer.chunked(9)))
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        assertEquals(1, agent.requests.size, "Repeating a request that hit the same token limit truncates it again")
        assertTrue(screen.rejections.isEmpty(), screen.readerFeedback())
        assertNotNull(controller.snapshot(surface))
    }

    @Test
    fun aResponseThatIsOnlyTruncationIsRetried() = runTest {
        val truncated = """{"createSurface":{"surfaceId":"test","rootId":"t","components":[{"id":"""
        val agent = ScriptedTextGen(
            listOf(listOf(truncated + "\n"), listOf(validSurface() + "\n")),
        )
        val screen = screen(surface)

        turn(agent, screen).run("show my tasks").collect { }

        assertEquals(2, agent.requests.size, "Nothing arrived, so asking again is the only option")
        assertTrue(screen.rejections.isEmpty(), screen.readerFeedback())
        assertNotNull(controller.snapshot(surface))
    }

    // ─── context ──────────────────────────────────────────────────────────

    @Test
    fun aFollowUpCarriesWhatCameBefore() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 9))
        val history = listOf(ChatTurnRecord("show my tasks", "Here are your tasks", true))

        turn(agent, screen(SurfaceId("second")), history).run("now only overdue").collect { }

        val second: String = agent.requests.first()
        assertTrue("show my tasks" in second, "The model needs the earlier turn: $second")
        assertTrue("now only overdue" in second, second)
    }

    @Test
    fun anAnswerThatDrewAScreenIsRememberedAsSuch() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 9))
        val history = listOf(ChatTurnRecord("show my tasks", "", true))

        turn(agent, screen(SurfaceId("second")), history).run("now only overdue").collect { }

        assertTrue("(showed a screen)" in agent.requests.first(), agent.requests.first())
    }

    @Test
    fun historyIsBoundedSoAConversationCannotGrowWithoutLimit() = runTest {
        val agent = ScriptedTextGen(fragments(validSurface(), 9))
        val history = (0 until 12).map { index: Int ->
            ChatTurnRecord("question $index", "answer $index", false)
        }

        turn(agent, screen(surface), history).run("now").collect { }

        val last: String = agent.requests.first()
        assertTrue("question 0" !in last, "The oldest turn falls out: $last")
    }

    // ─── harness ───────────────────────────────────────────────────────────

    private fun unknownComponentSurface(kind: String): String =
        """{"createSurface":{"surfaceId":"test","rootId":"x","components":[""" +
            """{"id":"x","kind":"$kind","series":[]}]}}"""

    private fun missingPropertySurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"h1","components":[""" +
            """{"id":"h1","kind":"heading"}]}}"""

    private fun fragments(answer: String, chunk: Int): List<List<String>> =
        listOf((answer + "\n").chunked(chunk))

    /** The same [answer] in [times] chunks-sized fragments, once per call. */
    private fun repeatedFragments(answer: String, chunk: Int, times: Int): List<List<String>> =
        List(times) { (answer + "\n").chunked(chunk) }

    /** One whole answer per call, in the order given. */
    private fun answers(vararg answers: String): List<List<String>> =
        answers.map { answer: String -> listOf(answer + "\n") }

    /** The same [answer] every time — a model that never gets it right. */
    private fun repeating(answer: String, times: Int): List<List<String>> =
        List(times) { listOf(answer + "\n") }

    private fun screen(id: SurfaceId): SurfaceTurn =
        SurfaceTurn(id, parser, processor, controller, SingularityCatalog)

    private fun turn(
        agent: TextGenPort,
        screen: SurfaceTurn,
        history: List<ChatTurnRecord> = emptyList(),
    ): ChatTurn = ChatTurn(agent = agent, surface = screen, history = history)

    private fun validSurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"root","components":[""" +
            """{"id":"root","kind":"column","children":["t1"]},""" +
            """{"id":"t1","kind":"text","value":"Hello"}]}}"""

    /**
     * The same surface, pretty-printed — one message over several lines.
     *
     * A real model asked for JSON Lines pretty-prints anyway, so the framer has to put the
     * lines back together; a fixture that stayed on one line would never exercise that.
     */
    private fun prettyPrintedSurface(): String = """
        {
          "createSurface": {
            "surfaceId": "test",
            "rootId": "root",
            "components": [
              {"id": "root", "kind": "column", "children": ["t1"]},
              {"id": "t1", "kind": "text", "value": "Hello"}
            ]
          }
        }
        """.trimIndent()

    private fun badSurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"chart","components":[""" +
            """{"id":"chart","kind":"chart","series":[1,2,3]}]}}"""

    /**
     * A model that records what it was asked, and answers in fragments.
     *
     * Answering in fragments is the point: a turn that only worked on whole lines would pass a
     * test written against a model that emits whole lines, and would fail in the app.
     */
    private class ScriptedTextGen(private val answers: List<List<String>>) : TextGenPort {
        val requests: MutableList<String> = mutableListOf()
        val instructionsSeen: MutableList<String> = mutableListOf()

        override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
            Result.success("unused")

        override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = flow {
            requests += message
            instructionsSeen += instructions
            val answer: List<String> = answers.getOrElse(requests.size - 1) { emptyList() }
            answer.forEach { chunk: String -> emit(AgentEvent.TextDelta(chunk)) }
            emit(AgentEvent.TurnCompleted)
        }

        override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
            Result.success(emptyList())
    }
}
