package com.singularity.todo.test.stubs

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.parser.UiEvent
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The demo payload is hand-written A2UI, and the flows assert on what it renders.
 *
 * Nothing else checked it: the parser was never handed this string, so a typo in it — a
 * wrong property name, a component the catalogue does not know — would have shown up only as
 * an empty sheet in the first flow that reached the device. These tests are that check,
 * moved in front of the device run.
 */
@Tag("fast")
class WhatsNewDemoPayloadTest {
    private fun parse(text: String): UiEvent.CreateSurface {
        val outcome = A2uiParser().parseLine(whatsNewDemoPayload(text))
        val parsed = assertIs<A2uiParseOutcome.Parsed>(outcome, "the demo payload has to parse")
        assertTrue(
            parsed.errors.isEmpty(),
            "the demo payload has to parse without errors, got ${parsed.errors}",
        )
        return assertIs<UiEvent.CreateSurface>(parsed.event, "the payload has to create a surface")
    }

    @Test
    fun `the payload is a column holding the note and a dismiss button`() {
        val surface = parse("Release notes demo")

        assertEquals("root", surface.rootId.id, "the column is the root of the surface")
        assertEquals(setOf("root", "t1", "b1"), surface.components.keys)

        val column = assertIs<UiNode.Column>(surface.components.getValue("root"))
        assertEquals(listOf("t1", "b1"), column.children.map { it.id })

        val text = assertIs<UiNode.Text>(surface.components.getValue("t1"))
        assertEquals("Release notes demo", text.value)

        val button = assertIs<UiNode.Button>(surface.components.getValue("b1"))
        assertEquals("Got it", button.label)
        assertEquals("dismiss", button.action, "the button has to close the sheet")
    }

    @Test
    fun `the note is escaped, so a quote in it cannot break the payload`() {
        val surface = parse("He said \"ship it\"")

        val text = assertIs<UiNode.Text>(surface.components.getValue("t1"))
        assertEquals("He said \"ship it\"", text.value)
    }
}
