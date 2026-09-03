package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceControllerTest {

    @Test
    fun `surfaces starts empty`() {
        val ctrl = SurfaceController()
        assertEquals(emptyMap<SurfaceId, Surface>(), ctrl.surfaces.value)
    }

    @Test
    fun `apply CreateSurface adds surface to map`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf(
                    "r1" to UiNode.Text("Hello"),
                ),
            ),
        )
        assertEquals(1, ctrl.surfaces.value.size)
        assertEquals(SurfaceId("s1"), ctrl.surfaces.value[SurfaceId("s1")]?.id)
    }

    @Test
    fun `apply UpdateComponents merges into existing surface`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf(
                    "r1" to UiNode.Text("Hello"),
                ),
            ),
        )
        ctrl.apply(
            UiEvent.UpdateComponents(
                surfaceId = SurfaceId("s1"),
                components = mapOf(
                    "r2" to UiNode.Text("World"),
                ),
            ),
        )
        val surface = ctrl.surfaces.value[SurfaceId("s1")]
        assertNotNull(surface)
        assertEquals(2, surface!!.components.size)
        assertNotNull(surface.components["r2"])
    }

    @Test
    fun `apply UpdateData updates dataModel`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf(
                    "r1" to UiNode.TextField(
                        label = "Name",
                        path = com.singularity.todo.feature.genui.schema.UiPath.of("name"),
                        initial = "Bob",
                    ),
                ),
            ),
        )
        ctrl.apply(
            UiEvent.UpdateData(
                surfaceId = SurfaceId("s1"),
                path = com.singularity.todo.feature.genui.schema.UiPath.of("name"),
                value = kotlinx.serialization.json.JsonPrimitive("Alice"),
            ),
        )
        val surface = ctrl.surfaces.value[SurfaceId("s1")]
        assertNotNull(surface)
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive("Alice"),
            surface!!.dataModel.get(com.singularity.todo.feature.genui.schema.UiPath.of("name")),
        )
    }

    @Test
    fun `apply DeleteSurface removes surface`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )
        ctrl.apply(UiEvent.DeleteSurface(SurfaceId("s1")))
        assertEquals(emptyMap<SurfaceId, Surface>(), ctrl.surfaces.value)
    }

    @Test
    fun `apply ParseError does not crash and does not change state`() {
        val ctrl = SurfaceController()
        ctrl.apply(UiEvent.ParseError(input = "bad json", message = "invalid"))
        assertEquals(emptyMap<SurfaceId, Surface>(), ctrl.surfaces.value)
    }

    @Test
    fun `reset clears all surfaces`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )
        ctrl.reset()
        assertEquals(emptyMap<SurfaceId, Surface>(), ctrl.surfaces.value)
    }

    @Test
    fun `rootNode returns root node for existing surface`() {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )
        val root = ctrl.rootNode(SurfaceId("s1"))
        assertNotNull(root)
        assert(root is UiNode.Text)
    }

    @Test
    fun `rootNode returns null for unknown surface`() {
        val ctrl = SurfaceController()
        assertNull(ctrl.rootNode(SurfaceId("unknown")))
    }

    @Test
    fun `surfaces flow emits on event`(): Unit = runBlocking {
        val ctrl = SurfaceController()
        val first = ctrl.surfaces.first()
        assertEquals(emptyMap<SurfaceId, Surface>(), first)

        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )
        // Value should now have s1
        assertEquals(1, ctrl.surfaces.value.size)
    }
}
