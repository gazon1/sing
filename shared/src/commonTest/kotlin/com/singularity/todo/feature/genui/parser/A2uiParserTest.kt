package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.toPointer
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class A2uiParserTest {

    private val parser = A2uiParser()

    @Test
    fun parseLineReturnsNullForBlankLines() {
        assertNull(parser.parseLine(""))
        assertNull(parser.parseLine("   "))
    }

    @Test
    fun parseLineReturnsNullForInvalidJson() {
        assertNull(parser.parseLine("not json"))
        assertNull(parser.parseLine("{"))
    }

    @Test
    fun parseLineParsesCreateSurface() {
        val line = """
            {"createSurface":{"surfaceId":"s1","rootId":"r1","components":[
                {"id":"r1","kind":"text","value":"Hello"}
            ]}}
        """.trimIndent()
        val event = parser.parseLine(line)
        assertNotNull(event)
        assert(event is UiEvent.CreateSurface)
        val create = event as UiEvent.CreateSurface
        assertEquals(SurfaceId("s1"), create.surfaceId)
        assertEquals(NodeRef("r1"), create.rootId)
        assertEquals(1, create.components.size)
        assert(create.components["r1"] is UiNode.Text)
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
        val event = parser.parseLine(line)
        assertNotNull(event)
        val create = event as UiEvent.CreateSurface
        assert(create.components["c1"] is UiNode.Column)
        assert(create.components["t1"] is UiNode.Text)
        assert(create.components["b1"] is UiNode.Button)
    }

    @Test
    fun parseLineParsesUpdateComponents() {
        val line = """
            {"updateComponents":{"surfaceId":"s1","components":[
                {"id":"t2","kind":"text","value":"Updated"}
            ]}}
        """.trimIndent()
        val event = parser.parseLine(line)
        assert(event is UiEvent.UpdateComponents)
        val update = event as UiEvent.UpdateComponents
        assertEquals(SurfaceId("s1"), update.surfaceId)
        assertEquals(1, update.components.size)
    }

    @Test
    fun parseLineParsesUpdateData() {
        val line = """{"updateData":{"surfaceId":"s1","path":"name","value":"Alice"}}"""
        val event = parser.parseLine(line)
        assert(event is UiEvent.UpdateData)
        val update = event as UiEvent.UpdateData
        assertEquals(SurfaceId("s1"), update.surfaceId)
        assertEquals("name", update.path.toPointer())
        assertEquals("Alice", update.value.jsonPrimitive.content)
    }

    @Test
    fun parseLineParsesDeleteSurface() {
        val line = """{"deleteSurface":{"surfaceId":"s1"}}"""
        val event = parser.parseLine(line)
        assert(event is UiEvent.DeleteSurface)
        assertEquals(SurfaceId("s1"), (event as UiEvent.DeleteSurface).surfaceId)
    }

    @Test
    fun parseLineReturnsNullForUnknownOperation() {
        val line = """{"unknownOp":{}}"""
        assertNull(parser.parseLine(line))
    }

    @Test
    fun parseLineParsesBadgeWithTone() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"b1","components":[
            {"id":"b1","kind":"badge","text":"Done","tone":"Positive"}
        ]}}"""
        val event = parser.parseLine(line)
        val create = event as UiEvent.CreateSurface
        val badge = create.components["b1"] as UiNode.Badge
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
        val event = parser.parseLine(line)
        val create = event as UiEvent.CreateSurface
        val tabs = create.components["tabs1"] as UiNode.Tabs
        assertEquals(2, tabs.tabs.size)
        assertEquals("Tab A", tabs.tabs[0].title)
        assertEquals(NodeRef("c1"), tabs.tabs[0].child)
    }

    @Test
    fun parseLineParsesCheckboxWithInitialTrue() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"cb1","components":[
            {"id":"cb1","kind":"checkbox","label":"Done","path":"done","initial":true}
        ]}}"""
        val event = parser.parseLine(line)
        val create = event as UiEvent.CreateSurface
        val cb = create.components["cb1"] as UiNode.Checkbox
        assertEquals("Done", cb.label)
        assertEquals(true, cb.initial)
    }

    @Test
    fun parseLineParsesIcon() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"i1","components":[
            {"id":"i1","kind":"icon","name":"star"}
        ]}}"""
        val event = parser.parseLine(line)
        val create = event as UiEvent.CreateSurface
        val icon = create.components["i1"] as UiNode.Icon
        assertEquals("star", icon.name)
    }

    @Test
    fun parseLineParsesTextFieldWithPathAndInitial() {
        val line = """{"createSurface":{"surfaceId":"s1","rootId":"tf1","components":[
            {"id":"tf1","kind":"text_field","label":"Name","path":"items/0/name","initial":"Bob"}
        ]}}"""
        val event = parser.parseLine(line)
        val create = event as UiEvent.CreateSurface
        val tf = create.components["tf1"] as UiNode.TextField
        assertEquals("Name", tf.label)
        assertEquals("Bob", tf.initial)
        assertEquals("items/0/name", tf.path.toPointer())
    }
}
