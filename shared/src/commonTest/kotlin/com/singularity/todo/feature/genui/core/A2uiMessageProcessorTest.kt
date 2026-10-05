package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The processor is the one place that decides what reached the screens, so these cover the three
 * ways a message can end: applied whole, applied partly, or refused.
 */
@Tag("fast")
class A2uiMessageProcessorTest {

    private val controller = SurfaceController()
    private val parser = A2uiParser(SingularityCatalog)
    private val processor = A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller)

    @Test
    fun aValidMessageIsApplied() {
        val result = processor.process(create("s1", "root" to """{"id":"root","kind":"text","value":"Hi"}"""), parser)
        assertTrue(result.eventOrNull != null)
        assertTrue(result is A2uiParseOutcome.Parsed && result.errors.isEmpty())
        assertNotNull(controller.snapshot(SurfaceId("s1")))
    }

    @Test
    fun aRejectedComponentIsReportedAndTheRestIsApplied() {
        val line = create(
            "s1",
            "root" to """{"id":"root","kind":"column","children":["a","b"]}""",
            "a" to """{"id":"a","kind":"text","value":"kept"}""",
            "b" to """{"id":"b","kind":"chart","series":[]}""",
        )
        val result = processor.process(line, parser)
        val applied = controller.snapshot(SurfaceId("s1"))
        assertNotNull(applied)
        assertEquals(2, applied.components.size, "The valid components are on screen")
        assertTrue("b" !in applied.components, "The invalid one is not")
        val error = result.errorOrNull ?: (result as A2uiParseOutcome.Parsed).errors.single()
        assertEquals(A2uiErrorCode.UNKNOWN_COMPONENT, error.code)
    }

    @Test
    fun anUpdateForASurfaceThatDoesNotExistIsRefusedWithAReason() {
        val line = """{"updateComponents":{"surfaceId":"absent","components":[{"id":"t","kind":"text","value":"x"}]}}"""
        val result = processor.apply((parser.parseLine(line) as A2uiParseOutcome.Parsed).event)
        assertFalse(result.applied)
        assertEquals(A2uiErrorCode.UNKNOWN_SURFACE, result.errors.single().code)
    }

    @Test
    fun creatingTheSameSurfaceTwiceIsRefusedRatherThanReplaced() {
        processor.apply(
            (
                parser.parseLine(
                    create("s1", "root" to """{"id":"root","kind":"text","value":"one"}"""),
                ) as A2uiParseOutcome.Parsed
            ).event,
        )
        val again = processor.apply(
            (
                parser.parseLine(
                    create("s1", "root" to """{"id":"root","kind":"text","value":"two"}"""),
                ) as A2uiParseOutcome.Parsed
            ).event,
        )
        assertFalse(again.applied, "Replacing a surface under a streaming model leaves the two halves disagreeing")
        val surface = assertNotNull(controller.snapshot(SurfaceId("s1")))
        assertEquals("one", (surface.components["root"] as UiNode.Text).value)
    }

    @Test
    fun aCycleIsBrokenBeforeItReachesTheScreens() {
        val line = create(
            "s1",
            "a" to """{"id":"a","kind":"column","children":["b"]}""",
            "b" to """{"id":"b","kind":"column","children":["a"]}""",
        )
        val result = processor.apply((parser.parseLine(line) as A2uiParseOutcome.Parsed).event)
        assertTrue(result.errors.any { it.code == A2uiErrorCode.CYCLIC_REFERENCE })
        val surface = assertNotNull(controller.snapshot(SurfaceId("s1")))
        assertEquals(1, surface.components.size, "The cycle is broken on the way in")
    }

    @Test
    fun dataIsAppliedAndReadable() {
        processor.apply(
            (
                parser.parseLine(
                    create("s1", "root" to """{"id":"root","kind":"text","value":"x"}"""),
                ) as A2uiParseOutcome.Parsed
            ).event,
        )
        val data = parser.parseLine("""{"updateData":{"surfaceId":"s1","path":"/user/name","value":"Ada"}}""")
        processor.apply((data as A2uiParseOutcome.Parsed).event)
        val model = assertNotNull(controller.snapshot(SurfaceId("s1"))).dataModel
        assertEquals(
            "Ada",
            model.get(com.singularity.todo.feature.genui.schema.UiPath.parse("/user/name"))?.toString()?.trim('"'),
        )
    }

    private fun create(surfaceId: String, vararg components: Pair<String, String>): String {
        val joined = components.joinToString(",") { it.second }
        return """{"createSurface":{"surfaceId":"$surfaceId",""" +
            """"rootId":"${components.first().first}","components":[$joined]}}"""
    }
}
