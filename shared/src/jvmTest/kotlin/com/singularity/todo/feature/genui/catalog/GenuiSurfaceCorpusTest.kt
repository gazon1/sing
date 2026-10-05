package com.singularity.todo.feature.genui.catalog

import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiSeverity
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screens we expect to be able to express, kept as real responses and checked against the real
 * pipeline.
 *
 * This is the cheap half of measuring generation. It cannot tell us whether a model produces
 * valid output — that needs a live provider and a metric — but it pins the other end: the catalog
 * can still express the screens we ask for. A component removed, a property renamed, or a child
 * rule tightened shows up here as a failure rather than as a screen the model can no longer build
 * and nobody notices until a user asks for one.
 *
 * The corpus is therefore written in the same dialect the model is asked for, including data written
 * after the components that bind it — the ordering a real streaming response has.
 *
 * Tagged `slow` because it reads the corpus off disk: that is a real file, and the fast tag means
 * "pure, in-process", not "quick".
 */
@Tag("slow")
class GenuiSurfaceCorpusTest {

    private val parser = A2uiParser(SingularityCatalog)
    private val controller = SurfaceController()
    private val processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller)

    @Test
    fun everyCorpusScreenIsAcceptedWithoutCorrections() {
        val rejections: MutableList<String> = mutableListOf()
        for (name in CORPUS) {
            for ((index, line) in corpusLines(name).withIndex()) {
                val outcome = parser.parseLine(line)
                assertTrue(outcome is A2uiParseOutcome.Parsed, "$name line ${index + 1} did not parse: $outcome")
                val parsed = outcome as A2uiParseOutcome.Parsed
                assertTrue(parsed.errors.isEmpty(), "$name line ${index + 1}: ${parsed.errors.map { it.asFeedback() }}")
                val result = processor.apply(parsed.event, parsed.errors)
                result.errors
                    .filterNot { it.severity == A2uiSeverity.ADVISORY }
                    .forEach { rejections += "$name line ${index + 1}: ${it.asFeedback()}" }
            }
        }
        assertTrue(rejections.isEmpty(), "The catalog can no longer express our own screens:\n$rejections")
    }

    @Test
    fun aCorpusScreenLeavesSomethingOnScreen() {
        for (name in CORPUS) {
            val fresh = SurfaceController()
            val local = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), fresh)
            corpusLines(name).forEach { line ->
                val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
                local.apply(parsed.event, parsed.errors)
            }
            val surfaces = fresh.surfaces.value
            assertTrue(surfaces.isNotEmpty(), "$name rendered nothing at all")
            assertTrue(
                surfaces.values.any { it.components.size > 1 },
                "$name produced a screen with a single component — usually a sign it was cut short",
            )
        }
    }

    @Test
    fun theCorpusCoversTheDomainComponents() {
        // The domain components are the ones a model is most likely to reach for, and the ones most
        // likely to be renamed in a refactor. Their absence from the corpus is how that would go
        // unnoticed.
        val corpus: String = CORPUS.joinToString("\n") {
            File("src/jvmTest/resources/genui-corpus/$it.jsonl").readText()
        }
        for (kind in listOf("task_card", "due_date", "project_chip", "text_field", "list", "button")) {
            assertTrue("\"$kind\"" in corpus, "The corpus no longer exercises $kind")
        }
    }

    @Test
    fun everyCorpusFileExists() {
        for (name in CORPUS) {
            assertTrue(
                File("src/jvmTest/resources/genui-corpus/$name.jsonl").isFile,
                "$name is listed in the corpus but not on disk",
            )
        }
    }

    @Test
    fun noCorpusScreenBorrowsAnotherScreensIdentifier() {
        // Two screens sharing an identifier is what per-answer ownership prevents. It is a
        // property of the corpus rather than of a line: one screen is several messages by design,
        // so its createSurface and the updateData that follows it repeat the same id on purpose.
        // What must not happen is one file's screen answering to another's name.
        val owners: MutableMap<SurfaceId, String> = mutableMapOf()
        for (name in CORPUS) {
            for (line in corpusLines(name)) {
                val id: SurfaceId = (parser.parseLine(line) as A2uiParseOutcome.Parsed).event.surfaceId()
                val previous: String? = owners.put(id, name)
                assertTrue(previous == null || previous == name, "$name reuses '$id', which belongs to $previous")
            }
        }
        assertEquals(CORPUS.size, owners.values.toSet().size, "Every corpus file draws its own screen")
    }

    private fun corpusLines(name: String): List<String> =
        File("src/jvmTest/resources/genui-corpus/$name.jsonl")
            .readLines()
            .filter { it.isNotBlank() }

    private companion object {
        val CORPUS: List<String> = listOf("task-list", "add-task-form")
    }
}

private fun UiEvent.surfaceId(): SurfaceId = when (this) {
    is UiEvent.CreateSurface -> surfaceId
    is UiEvent.UpdateComponents -> surfaceId
    is UiEvent.UpdateData -> surfaceId
    is UiEvent.DeleteSurface -> surfaceId
}
