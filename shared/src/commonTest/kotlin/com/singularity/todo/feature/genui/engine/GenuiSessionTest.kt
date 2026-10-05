package com.singularity.todo.feature.genui.engine

import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.feature.genui.transport.GenuiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The correction loop, end to end, against a transport that fragments its output the way a real
 * streaming provider does.
 *
 * The engine this replaces had no caller, and the transport it would have used emitted fragments
 * the parser could not consume. A test at this level is the only thing that would have caught
 * either, because both are invisible to a test of the parser alone and to a test of the transport
 * alone.
 */
@Tag("fast")
class GenuiSessionTest {

    private val controller = SurfaceController()
    private val parser = A2uiParser(SingularityCatalog)
    private val processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller)

    private val surface = SurfaceId("test")

    @Test
    fun aFragmentedResponseProducesASurface() = runTest {
        val transport = fragments(validSurface(), 9)
        val outcome = session(transport).respond("show my tasks", surface)

        assertFalse(outcome.failed, outcome.feedback())
        assertEquals(1, outcome.attempts, "A valid answer is not corrected")
        assertNotNull(controller.snapshot(surface), "The surface is on screen")
    }

    @Test
    fun proseBecomesTheReplyAndTheSurfaceIsSeparate() = runTest {
        val response = "Here are your tasks:\n${validSurface()}\nAnything else?"
        val outcome = session(fragments(response, 11)).respond("show my tasks", surface)
        assertTrue("Here are your tasks:" in outcome.text, outcome.text)
        assertTrue("Anything else?" in outcome.text, outcome.text)
        assertNotNull(controller.snapshot(surface))
    }

    @Test
    fun aHallucinatedComponentCostsOneExtraCallAndThenSucceeds() = runTest {
        val transport = answers(badSurface(), validSurface())
        val outcome = session(transport).respond("show my tasks", surface)

        assertFalse(outcome.failed, outcome.feedback())
        assertEquals(2, outcome.attempts, "Exactly one correction")
        assertTrue(
            transport.requests[1].contains("UNKNOWN_COMPONENT"),
            "The model is told what was wrong: ${transport.requests[1]}",
        )
        assertTrue(
            transport.requests[1].contains("text"),
            "The model is told which components exist: ${transport.requests[1]}",
        )
    }

    @Test
    fun aModelThatNeverConvergesStopsAtTheBoundAndReportsIt() = runTest {
        val transport = repeating(badSurface(), 5)
        val outcome = session(transport).respond("show my tasks", surface)

        assertTrue(outcome.failed, "An unbounded loop would cost tokens forever")
        assertEquals(GenuiSession.DEFAULT_MAX_CORRECTION_TURNS + 1, outcome.attempts)
        assertTrue(transport.requests.size <= 3, "At most one call plus two corrections: ${transport.requests.size}")
        assertTrue(outcome.errors.any { it.code == A2uiErrorCode.UNKNOWN_COMPONENT })
    }

    @Test
    fun aFailedTurnLeavesNothingHalfApplied() = runTest {
        val transport = repeating(badSurface(), 5)
        session(transport).respond("show my tasks", surface)
        val snapshot = controller.snapshot(surface)
        // The rejected component was never in a message that applied, so there is nothing to find.
        assertTrue(snapshot == null || snapshot.components.isEmpty(), snapshot?.components.toString())
    }

    @Test
    fun theCatalogInstructionsArePartOfTheSystemPrompt() = runTest {
        val transport = fragments(validSurface(), 13)
        session(transport).respond("show my tasks", surface)
        assertTrue("Components" in transport.systemPrompts.first(), "The catalog must reach the model")
        assertTrue("task_card" in transport.systemPrompts.first(), "The domain components must be offered")
    }

    @Test
    fun theOriginalPromptIsRepeatedSoTheModelKnowsWhatItWasBuilding() = runTest {
        val transport = answers(badSurface(), validSurface())
        session(transport).respond("show my tasks", surface)
        assertTrue(transport.requests[1].contains("show my tasks"), transport.requests[1])
    }

    // Each answer ends with a newline. A message with no terminator was cut off by the model, and
    // the framer reports that rather than guessing at the rest — so a fixture without one would be
    // testing truncation, not the thing it was written for.
    // ─── C1: a surface belongs to one answer ──────────────────────────────

    @Test
    fun twoAnswersDoNotShareASurface() = runTest {
        val transport = repeatedFragments(validSurface(), 9, times = 2)
        val first = SurfaceId("first")
        val second = SurfaceId("second")
        val session = session(transport)

        session.respond("show my tasks", first)
        session.respond("now only overdue", second)

        assertNotNull(controller.snapshot(first), "The first answer keeps its own screen")
        assertNotNull(controller.snapshot(second), "The second answer has its own")
        assertTrue(first != second)
    }

    /**
     * The identifier the model wrote inside the message is not the address the surface ends up at.
     *
     * Both answers here declare `"surfaceId":"test"` — the same name a model reaches for every time.
     * If the wire name won, the second answer would either collide with the first or overwrite it,
     * and the message that asked for the first screen would redraw the second one.
     */
    @Test
    fun theIdentifierTheModelWroteIsNotTheAddressTheSurfaceEndsUpAt() = runTest {
        val transport = repeatedFragments(validSurface(), 9, times = 2)
        val first = SurfaceId("first")
        val second = SurfaceId("second")
        val session = session(transport)

        session.respond("show my tasks", first)
        val firstRoot: UiNode.Column? = controller.snapshot(first)?.components?.get("root") as? UiNode.Column
        session.respond("now only overdue", second)

        assertNotNull(controller.snapshot(first), "The first answer still has a screen of its own")
        assertNotNull(controller.snapshot(second), "And so does the second")
        assertEquals(firstRoot, controller.snapshot(first)?.components?.get("root"), "It was not redrawn")
        assertEquals(2, controller.surfaces.value.size, "Both live side by side")
    }

    @Test
    fun anAnswerWithNoScreenLeavesEarlierOnesAlone() = runTest {
        val transport = RecordingTransport(listOf(listOf(validSurface() + "\n"), listOf("Just a sentence.\n")))
        val first = SurfaceId("first")
        val second = SurfaceId("second")
        val session = session(transport)

        session.respond("show my tasks", first)
        val outcome = session.respond("what is the weather", second)

        assertFalse(outcome.failed)
        assertEquals(null, outcome.surfaceId, "An answer with no screen names no surface")
        assertNotNull(controller.snapshot(first), "A prose answer must not empty an earlier screen")
    }

    // ─── C3: the model is told about this attempt only ─────────────────────

    @Test
    fun aCorrectionNamesOnlyTheLatestRejections() = runTest {
        // First answer is wrong, second is wrong in a different way, third is right.
        val transport = RecordingTransport(
            listOf(
                listOf(unknownComponentSurface("chart").replace("chart", "chart") + "\n"),
                listOf(missingPropertySurface() + "\n"),
                listOf(validSurface() + "\n"),
            ),
        )
        session(transport).respond("show my tasks", surface)

        assertEquals(3, transport.requests.size)
        val secondRequest: String = transport.requests[1]
        val thirdRequest: String = transport.requests[2]
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
        val transport = RecordingTransport(listOf(answer.chunked(9)))
        val outcome = session(transport).respond("show my tasks", surface)

        assertEquals(1, outcome.attempts, "Repeating a request that hit the same token limit truncates it again")
        assertFalse(outcome.failed)
        assertNotNull(controller.snapshot(surface))
    }

    @Test
    fun aResponseThatIsOnlyTruncationIsRetried() = runTest {
        val truncated = """{"createSurface":{"surfaceId":"test","rootId":"t","components":[{"id":"""
        val transport = RecordingTransport(listOf(listOf(truncated + "\n"), listOf(validSurface() + "\n")))
        val outcome = session(transport).respond("show my tasks", surface)

        assertEquals(2, outcome.attempts, "Nothing arrived, so asking again is the only option")
        assertFalse(outcome.failed)
        assertNotNull(controller.snapshot(surface))
    }

    // ─── context ──────────────────────────────────────────────────────────

    @Test
    fun aFollowUpCarriesWhatCameBefore() = runTest {
        val transport = fragments(validSurface(), 9)
        val session = session(transport)
        session.respond("show my tasks", surface)
        session.respond("now only overdue", SurfaceId("second"))

        val second: String = transport.requests[1]
        assertTrue("show my tasks" in second, "The model needs the earlier turn: $second")
        assertTrue("now only overdue" in second, second)
    }

    @Test
    fun historyIsBoundedSoAConversationCannotGrowWithoutLimit() = runTest {
        val transport = fragments(validSurface(), 9)
        val session = session(transport)
        repeat(GenuiSession.MAX_HISTORY + 4) { index ->
            session.respond("question $index", SurfaceId("s$index"))
        }
        val last: String = transport.requests.last()
        assertTrue("question 0" !in last, "The oldest turn falls out: $last")
    }

    private fun unknownComponentSurface(kind: String): String =
        """{"createSurface":{"surfaceId":"test","rootId":"x","components":[""" +
            """{"id":"x","kind":"$kind","series":[]}]}}"""

    private fun missingPropertySurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"h1","components":[""" +
            """{"id":"h1","kind":"heading"}]}}"""

    private fun fragments(answer: String, chunk: Int): RecordingTransport =
        RecordingTransport(listOf((answer + "\n").chunked(chunk)))

    /** The same [answer] in [times] chunks-sized fragments, once per call. */
    private fun repeatedFragments(answer: String, chunk: Int, times: Int): RecordingTransport =
        RecordingTransport(List(times) { (answer + "\n").chunked(chunk) })

    /** One whole answer per call, in the order given. */
    private fun answers(vararg answers: String): RecordingTransport =
        RecordingTransport(answers.map { answer: String -> listOf(answer + "\n") })

    /** The same [answer] every time — a model that never gets it right. */
    private fun repeating(answer: String, times: Int): RecordingTransport =
        RecordingTransport(List(times) { listOf(answer + "\n") })

    private fun session(transport: GenuiTransport): GenuiSession =
        GenuiSession(transport, parser, processor, controller, SingularityCatalog)

    private fun validSurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"root","components":[""" +
            """{"id":"root","kind":"column","children":["t1"]},""" +
            """{"id":"t1","kind":"text","value":"Hello"}]}}"""

    private fun badSurface(): String =
        """{"createSurface":{"surfaceId":"test","rootId":"chart","components":[""" +
            """{"id":"chart","kind":"chart","series":[1,2,3]}]}}"""

    /**
     * A transport that records what it was asked, and answers in fragments.
     *
     * Answering in fragments is the point: a session that only worked on whole lines would pass a
     * test written against a transport that emits whole lines, and would fail in the app.
     */
    private class RecordingTransport(private val answers: List<List<String>>) : GenuiTransport {
        val requests: MutableList<String> = mutableListOf()
        val systemPrompts: MutableList<String> = mutableListOf()

        override suspend fun send(prompt: String, systemPrompt: String): Flow<String> {
            requests += prompt
            systemPrompts += systemPrompt
            val answer: List<String> = answers.getOrElse(requests.size - 1) { emptyList() }
            return flowOf(*answer.toTypedArray())
        }
    }
}
