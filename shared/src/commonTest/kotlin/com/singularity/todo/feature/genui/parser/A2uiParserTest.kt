package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.schema.toPointer
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The parser used to answer `UiEvent?`, so every one of these cases ended in the same `null` and
 * the first block of tests below exists to prove they no longer do. An unknown component and a
 * missing required property produced silence before, and silence is what made a model unable to
 * correct itself.
 */
@Tag("fast")
class A2uiParserTest {

    private val parser = A2uiParser()

    // ─── Classification: nothing is silently absent ────────────────────────

    @Test
    fun blankLineIsSkipped() {
        assertTrue(parser.parseLine("") is A2uiParseOutcome.Skipped)
        assertTrue(parser.parseLine("   ") is A2uiParseOutcome.Skipped)
    }

    /**
     * A line that cannot be read is a rejection, not a skip.
     *
     * The framer routes prose away, so anything that reaches here as an object was meant to be a
     * message. Reported as a skip it never became an error, the correction loop saw an empty
     * rejection list and accepted the answer as a clean success — with nothing on screen.
     */
    @Test
    fun unreadableJsonIsRejectedRatherThanSkipped() {
        val truncated = parser.parseLine("{")
        assertTrue(truncated is A2uiParseOutcome.Failed, "$truncated")
        assertEquals(A2uiErrorCode.MALFORMED_LINE, (truncated as A2uiParseOutcome.Failed).error.code)

        val notJson = parser.parseLine("""{"a": }""")
        assertTrue(notJson is A2uiParseOutcome.Failed, "$notJson")
    }

    @Test
    fun jsonThatIsNotAnObjectIsRejected() {
        assertTrue(parser.parseLine("""["a","b"]""") is A2uiParseOutcome.Failed)
        assertTrue(parser.parseLine("42") is A2uiParseOutcome.Failed)
    }

    @Test
    fun unknownOperationFailsWithTheKnownOperationsListed() {
        val outcome = parser.parseLine("""{"unknownOp":{}}""")
        val failed = outcome as? A2uiParseOutcome.Failed
        assertNotNull(failed, "An unknown operation must be reported, not dropped")
        assertEquals(A2uiErrorCode.UNKNOWN_OPERATION, failed.error.code)
        assertTrue("createSurface" in failed.error.allowed, "The model needs the legal operations")
    }

    @Test
    fun unknownOperationBodyIsNotForcedThroughTheParser() {
        // The first-key-is-the-operation rule forced the leading field through .jsonObject and threw
        // an IllegalArgumentException from inside a flow builder, for any object whose first field
        // was not a message body.
        val outcome = parser.parseLine("""{"note":"a leading field that is not a message","x":1}""")
        assertTrue(outcome is A2uiParseOutcome.Failed)
    }

    @Test
    fun leadingNonBodyFieldDoesNotPreventParsing() {
        val line = """
            {"version":"v0.9","createSurface":{"surfaceId":"s1","rootId":"t1","components":[
                {"id":"t1","kind":"text","value":"Hello"}
            ]}}
        """.trimIndent()
        val outcome = parser.parseLine(line)
        val parsed = outcome as? A2uiParseOutcome.Parsed
        assertNotNull(parsed, "The operation is found by name, not by position: $outcome")
        assertEquals(1, parsed.event.componentsSize())
    }

    @Test
    fun newerSchemaVersionIsRejectedWithItsNumber() {
        val line = """{"schemaVersion":99,"createSurface":{"surfaceId":"s1","rootId":"t1","components":[]}}"""
        val failed = parser.parseLine(line) as? A2uiParseOutcome.Failed
        assertNotNull(failed)
        assertEquals(A2uiErrorCode.UNSUPPORTED_SCHEMA_VERSION, failed.error.code)
    }

    @Test
    fun missingSurfaceIdFailsTheWholeMessage() {
        val line = """{"createSurface":{"rootId":"t1","components":[]}}"""
        val failed = parser.parseLine(line) as? A2uiParseOutcome.Failed
        assertNotNull(failed)
        assertTrue("/createSurface/surfaceId" in failed.error.pointer.orEmpty(), failed.error.pointer.orEmpty())
    }

    // ─── Happy paths ───────────────────────────────────────────────────────

    @Test
    fun parseLineParsesCreateSurface() {
        val line = """
            {"createSurface":{"surfaceId":"s1","rootId":"r1","components":[
                {"id":"r1","kind":"text","value":"Hello"}
            ]}}
        """.trimIndent()
        val create = parser.parseLine(line).requireCreate()
        assertEquals(SurfaceId("s1"), create.surfaceId)
        assertEquals(NodeRef("r1"), create.rootId)
        assertEquals(1, create.components.size)
        assertTrue(create.components["r1"] is UiNode.Text)
    }

    @Test
    fun parseLineParsesCreateSurfaceWithMultipleComponents() {
        val line = """
            {"createSurface":{"surfaceId":"s2","rootId":"c1","components":[
                {"id":"c1","kind":"column","children":["t1","b1"]},
                {"id":"t1","kind":"text","value":"Title"},
                {"id":"b1","kind":"button","label":"OK","action":"submit"}
            ]}}
        """.trimIndent()
        val create = parser.parseLine(line).requireCreate()
        assertTrue(create.components["c1"] is UiNode.Column)
        assertTrue(create.components["t1"] is UiNode.Text)
        assertTrue(create.components["b1"] is UiNode.Button)
    }

    @Test
    fun parseLineParsesUpdateComponents() {
        val line = """
            {"updateComponents":{"surfaceId":"s1","components":[
                {"id":"t2","kind":"text","value":"Updated"}
            ]}}
        """.trimIndent()
        val outcome = parser.parseLine(line)
        val update = outcome.eventOrNull as UiEvent.UpdateComponents
        assertEquals(SurfaceId("s1"), update.surfaceId)
        assertEquals(1, update.components.size)
    }

    @Test
    fun parseLineParsesUpdateData() {
        val line = """{"updateData":{"surfaceId":"s1","path":"name","value":"Alice"}}"""
        val update = parser.parseLine(line).eventOrNull as UiEvent.UpdateData
        assertEquals(SurfaceId("s1"), update.surfaceId)
        assertEquals("name", update.path.toPointer())
        assertEquals("Alice", update.value.jsonPrimitive.content)
    }

    @Test
    fun parseLineParsesDeleteSurface() {
        val line = """{"deleteSurface":{"surfaceId":"s1"}}"""
        val deleted = parser.parseLine(line).eventOrNull as UiEvent.DeleteSurface
        assertEquals(SurfaceId("s1"), deleted.surfaceId)
    }

    @Test
    fun parseLineParsesBadgeWithTone() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"b1","components":[
            {"id":"b1","kind":"badge","text":"Done","tone":"Positive"}
        ]}}"""
        val badge = parser.parseLine(line).requireCreate().components["b1"] as UiNode.Badge
        assertEquals("Done", badge.text)
        assertEquals(UiNode.Tone.Positive, badge.tone)
    }

    @Test
    fun parseLineParsesTabs() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"tabs1","components":[
            {"id":"tabs1","kind":"tabs","tabs":[
                {"title":"Tab A","child":"c1"},
                {"title":"Tab B","child":"c2"}
            ]},
            {"id":"c1","kind":"text","value":"Content A"},
            {"id":"c2","kind":"text","value":"Content B"}
        ]}}"""
        val tabs = parser.parseLine(line).requireCreate().components["tabs1"] as UiNode.Tabs
        assertEquals(2, tabs.tabs.size)
        assertEquals("Tab A", tabs.tabs[0].title)
        assertEquals(NodeRef("c1"), tabs.tabs[0].child)
    }

    @Test
    fun parseLineParsesCheckboxWithInitialTrue() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"cb1","components":[
            {"id":"cb1","kind":"checkbox","label":"Done","path":"done","initial":true}
        ]}}"""
        val checkbox = parser.parseLine(line).requireCreate().components["cb1"] as UiNode.Checkbox
        assertEquals("Done", checkbox.label)
        assertEquals(true, checkbox.initial)
    }

    @Test
    fun parseLineParsesIcon() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"i1","components":[
            {"id":"i1","kind":"icon","name":"star"}
        ]}}"""
        val icon = parser.parseLine(line).requireCreate().components["i1"] as UiNode.Icon
        assertEquals("star", icon.name)
    }

    @Test
    fun parseLineParsesTextFieldWithPathAndInitial() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"tf1","components":[
            {"id":"tf1","kind":"text_field","label":"Name","path":"items/0/name","initial":"Bob"}
        ]}}"""
        val field = parser.parseLine(line).requireCreate().components["tf1"] as UiNode.TextField
        assertEquals("Name", field.label)
        assertEquals("Bob", field.initial)
        assertEquals("items/0/name", field.path.toPointer())
    }

    // ─── Rejections that used to be silent ─────────────────────────────────

    @Test
    fun unknownComponentIsDroppedAndReportedWithTheLegalNames() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"chart1","components":[
            {"id":"chart1","kind":"chart","series":[1,2,3]},
            {"id":"t1","kind":"text","value":"Kept"}
        ]}}"""
        val outcome = parser.parseLine(line)
        val parsed = outcome as? A2uiParseOutcome.Parsed
        assertNotNull(parsed, "The valid component must still apply")
        assertEquals(1, parsed.event.componentsSize(), "The unknown component is dropped, not applied")
        assertEquals(1, parsed.errors.size)
        val error = parsed.errors.single()
        assertEquals(A2uiErrorCode.UNKNOWN_COMPONENT, error.code)
        assertTrue("text" in error.allowed, "The model needs the list of components that do exist")
    }

    @Test
    fun oneBadComponentDoesNotCostTheOthers() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"c1","components":[
            {"id":"c1","kind":"column","children":["t1","t2","t3","t4"]},
            {"id":"t1","kind":"text","value":"one"},
            {"id":"t2","kind":"text"},
            {"id":"t3","kind":"text","value":"three"},
            {"id":"t4","kind":"text","value":"four"}
        ]}}"""
        val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
        assertEquals(4, parsed.event.componentsSize(), "Four of five components are usable")
        val error = parsed.errors.single()
        assertEquals(A2uiErrorCode.MISSING_PROPERTY, error.code)
        assertTrue("value" in error.message, error.message)
        assertTrue("/createSurface/components/2/value" in error.pointer.orEmpty(), error.pointer.orEmpty())
    }

    @Test
    fun wrongPropertyTypeIsReported() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"h1","components":[
            {"id":"h1","kind":"heading","text":"Title","level":"two"}
        ]}}"""
        val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
        assertEquals(0, parsed.event.componentsSize())
        assertEquals(A2uiErrorCode.PROPERTY_TYPE_MISMATCH, parsed.errors.single().code)
    }

    @Test
    fun illegalEnumeratedValueListsTheLegalOnes() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"b1","components":[
            {"id":"b1","kind":"badge","text":"Hi","tone":"Rainbow"}
        ]}}"""
        val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
        val error = parsed.errors.single()
        assertEquals(A2uiErrorCode.PROPERTY_TYPE_MISMATCH, error.code)
        assertTrue("Positive" in error.allowed, error.allowed.toString())
    }

    @Test
    fun duplicateComponentIdIsReported() {
        val line = """{"updateComponents":{"surfaceId":"s1","components":[
            {"id":"t1","kind":"text","value":"first"},
            {"id":"t1","kind":"text","value":"second"}
        ]}}"""
        val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
        assertEquals(A2uiErrorCode.DUPLICATE_COMPONENT_ID, parsed.errors.single().code)
        assertEquals("second", ((parsed.event as UiEvent.UpdateComponents).components["t1"] as UiNode.Text).value)
    }

    @Test
    fun containerWithoutItsRequiredChildIsReported() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"card1","components":[
            {"id":"card1","kind":"card"}
        ]}}"""
        val parsed = parser.parseLine(line) as A2uiParseOutcome.Parsed
        assertEquals(A2uiErrorCode.MISSING_PROPERTY, parsed.errors.single().code)
    }

    // ─── Streaming ─────────────────────────────────────────────────────────

    @Test
    fun fragmentsAreReassembledIntoOneMessage() = runTest {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"t1","components":[""" +
            """{"id":"t1","kind":"text","value":"Hi"}]}}"""
        val fragments = (line + "\n").chunked(7)
        val outcomes = parser.parseTokens(flowOf(*fragments.toTypedArray())).toList()
        assertEquals(1, outcomes.size, "Seven fragments are one message, not seven")
        assertNotNull(outcomes.single().eventOrNull)
    }

    @Test
    fun proseAroundTheSurfaceIsSkippedRatherThanRejected() = runTest {
        val response = """
            Here are your tasks:
            ```json
            {"createSurface":{"surfaceId":"s1","rootId":"t1","components":[{"id":"t1","kind":"text","value":"Hi"}]}}
            ```
            Let me know if you want changes.
        """.trimIndent()
        val outcomes = parser.parseTokens(flowOf(response)).toList()
        assertEquals(1, outcomes.count { it.eventOrNull != null }, "The surface survives its wrapper")
        assertTrue(outcomes.all { it !is A2uiParseOutcome.Failed }, "Prose is not a contract violation")
    }

    @Test
    fun truncatedTrailingMessageIsReportedRatherThanParsed() = runTest {
        val truncated = """{"createSurface":{"surfaceId":"s1","rootId":"t1","components":[{"id":"t1","""
        val outcomes = parser.parseTokens(flowOf(truncated)).toList()
        assertTrue(outcomes.all { it.eventOrNull == null })
        assertTrue(outcomes.any { it is A2uiParseOutcome.Skipped }, "The cut-off is recorded")
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun A2uiParseOutcome.requireCreate(): UiEvent.CreateSurface {
        val parsed = this as? A2uiParseOutcome.Parsed
        assertNotNull(parsed, "Expected a parsed createSurface, got $this")
        assertTrue(parsed.errors.isEmpty(), "Unexpected rejections: ${parsed.errors.map { it.asFeedback() }}")
        return parsed.event as UiEvent.CreateSurface
    }

    private fun UiEvent.componentsSize(): Int = when (this) {
        is UiEvent.CreateSurface -> components.size
        is UiEvent.UpdateComponents -> components.size
        else -> 0
    }
}
