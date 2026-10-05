package com.singularity.todo.feature.genui.engine

import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.core.GenuiUsageCounter
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.feature.genui.transport.GenuiTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * First-attempt validity, as a regression test rather than a live metric.
 *
 * Every other GenUI test replays a fixture the test itself wrote, so the layer proved it could
 * *accept* a surface and nothing about whether it produces one. Measuring that against a real
 * provider needs a key and is not something a unit test can do.
 *
 * The shape that does work offline: a recorded response goes through the real session — real
 * parser, real validator, real correction loop — and the assertion is `attempts == 1`. A screen that
 * starts needing a second try becomes a failing test rather than a metric somebody has to remember
 * to look at, and it fails in the ordinary test loop instead of after a provider bill.
 *
 * What it does not measure, stated plainly: whether a model would produce *these* fixtures. A
 * recording is only as good as the response it captured, and it goes stale when the prompt changes.
 * What it does measure is what regressions actually break — that a change to the catalog, the
 * validator or the correction loop turns a previously valid response invalid, or needs a retry to
 * stay valid.
 */
@Tag("slow")
class GenuiFirstAttemptReplayTest {

    private val surface = SurfaceId("replay")
    private val controller = SurfaceController()

    /** One screen's recorded response, replayed and asserted to need no correction. */
    @Test
    fun aRecordedAnswerStillNeedsOnlyOneAttempt() = runTest {
        val outcome = session(transportReplaying(recording("task-list.jsonl"))).respond("show my tasks", surface)

        assertEquals(1, outcome.attempts, "A recorded good answer needs no correction: ${outcome.feedback()}")
        assertTrue(!outcome.failed, outcome.feedback())
        assertNotNull(controller.snapshot(surface), "And it draws a surface")
    }

    /**
     * The recording still exercises the catalog as it is today.
     *
     * Without this, a recording that has drifted away from the catalog would fail the test above for
     * the wrong reason — the fixture is stale, not the code — and the two failures would look alike.
     * Here, staleness *is* the finding.
     */
    @Test
    fun aRecordedAnswerStillUsesComponentsTheCatalogDeclares() = runTest {
        val usage = GenuiUsageCounter()
        val parser = A2uiParser(SingularityCatalog, usage = usage)
        val processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), SurfaceController())

        recording("task-list.jsonl").forEach { line: String ->
            val outcome: A2uiParseOutcome = parser.parseLine(line)
            if (outcome is A2uiParseOutcome.Parsed) {
                processor.apply(outcome.event.retargeted(surface), outcome.errors)
            }
        }

        assertTrue(usage.counts.value.isNotEmpty(), "The recording exercises something")
        val undeclared: List<String> =
            usage.counts.value.keys.filterNot { SingularityCatalog.components.containsKey(it) }
        assertTrue(
            undeclared.isEmpty(),
            "The recording uses components the catalog no longer declares: $undeclared",
        )
    }

    /**
     * A response that has become invalid is corrected, not quietly accepted.
     *
     * The counterpart to the test above. When the catalog moves on, a stale recording must fail
     * loudly rather than assert nothing. The surface is still partly drawn — the correction loop
     * leaves what it applied — so the assertion is on attempts and on the reason, not on the surface.
     */
    @Test
    fun anUnusableAnswerIsReportedRatherThanAcceptedQuietly() = runTest {
        val unknownKind = """{"createSurface":{"surfaceId":"s1","rootId":"r","components":[""" +
            """{"id":"r","kind":"sunburst_chart"}]}}"""
        val outcome = session(transportReplaying(listOf(unknownKind))).respond("show my chart", surface)

        assertTrue(outcome.attempts >= 2, "An unusable answer is corrected, not taken")
        assertTrue(
            outcome.feedback().contains("UNKNOWN_COMPONENT"),
            "and the model is told why: ${outcome.feedback()}",
        )
    }

    // ─── harness ───────────────────────────────────────────────────────────

    /**
     * A transport that emits [responses] once and then nothing, so a second attempt is visible as
     * an empty answer rather than as an infinite replay of the same mistake.
     */
    private fun transportReplaying(responses: List<String>): GenuiTransport = object : GenuiTransport {
        var calls: Int = 0
            private set

        override suspend fun send(prompt: String, systemPrompt: String): Flow<String> = flow {
            val answer: List<String> = if (calls++ == 0) responses else emptyList()
            // Each recorded line is terminated the way the wire terminates one. The corpus files do
            // not end in a newline, and replaying them verbatim leaves the last message unterminated
            // — which the framer correctly reports as a response cut off mid-message, and which has
            // nothing to do with what is being measured.
            answer.forEach { emit("$it\n") }
        }
    }

    /** A real session over the real pipeline, with one shared controller. */
    private fun session(transport: GenuiTransport): GenuiSession = GenuiSession(
        transport = transport,
        parser = A2uiParser(SingularityCatalog),
        processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller),
        controller = controller,
        rejectionCounter = GenuiRejectionCounter(),
    )

    /** The recorded model output, read from the corpus the offline test also uses. */
    private fun recording(name: String): List<String> =
        File("src/jvmTest/resources/genui-corpus/$name").readLines().filter { it.isNotBlank() }
}
