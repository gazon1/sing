package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the surface store holds, and what a change to one component leaves alone.
 *
 * The behaviour under test is the one the layer did not have: an update to one component must not
 * disturb another. When components were an immutable map inside an immutable surface, one update
 * replaced the map and every node in the tree re-rendered — which is why extending the catalogue
 * was expensive.
 */
@Tag("fast")
class SurfaceReactivityTest {
    private val controller = SurfaceController()
    private val surface = SurfaceId("s1")

    /** The components of the surface under test, as the store holds them right now. */
    private fun components(): Map<String, UiNode> =
        controller.snapshot(surface)?.componentStore?.snapshot() ?: emptyMap()

    @Test
    fun updatingOneComponentLeavesTheOthersUntouched() =
        runTest {
            controller.apply(create(text = mapOf("a" to "one", "b" to "two")))
            val before = components()

            controller.apply(update(text = mapOf("a" to "changed")))
            val after = components()

            assertEquals(before["b"], after["b"], "The unrelated component changed: $after")
            assertEquals("two", (after["b"] as UiNode.Text).value)
        }

    @Test
    fun updatingTheComponentReplacesItsNode() =
        runTest {
            controller.apply(create(text = mapOf("a" to "one", "b" to "two")))
            val store = controller.snapshot(surface)!!.componentStore
            val emissions: MutableList<Map<String, UiNode>> = mutableListOf()
            val job = launch { store.all.collect { emissions += it } }
            testScheduler.runCurrent()

            controller.apply(update(text = mapOf("a" to "changed")))
            testScheduler.runCurrent()

            assertEquals(2, emissions.size, "The store published the change: $emissions")
            assertEquals("changed", (components()["a"] as UiNode.Text).value)
            job.cancel()
        }

    @Test
    fun rewritingAnIdenticalComponentIsNotAChange() =
        runTest {
            controller.apply(create(text = mapOf("a" to "same")))
            val store = controller.snapshot(surface)!!.componentStore
            val emissions: MutableList<Map<String, UiNode>> = mutableListOf()
            val job = launch { store.all.collect { emissions += it } }
            testScheduler.runCurrent()

            controller.apply(update(text = mapOf("a" to "same")))
            testScheduler.runCurrent()

            assertEquals(1, emissions.size, "A no-op update is not a change: $emissions")
            job.cancel()
        }

    @Test
    fun aDataUpdateEmitsOnlyOnThePathThatChanged() =
        runTest {
            controller.apply(create(text = mapOf("a" to "one")))
            val watched: MutableList<kotlinx.serialization.json.JsonElement?> = mutableListOf()
            val untouched: MutableList<kotlinx.serialization.json.JsonElement?> = mutableListOf()
            val job = launch { controller.value(surface, UiPath.parse("/name")).collect { watched += it } }
            val other = launch { controller.value(surface, UiPath.parse("/other")).collect { untouched += it } }
            testScheduler.runCurrent()

            controller.apply(UiEvent.UpdateData(surface, UiPath.parse("/name"), JsonPrimitive("Ada")))
            testScheduler.runCurrent()

            assertEquals(2, watched.size, "The bound path emitted: $watched")
            assertEquals(1, untouched.size, "An unrelated path stayed quiet: $untouched")
            job.cancel()
            other.cancel()
        }

    @Test
    fun aSurfaceAppearingLaterBecomesVisible() =
        runTest {
            assertNull(controller.snapshot(surface), "The surface does not exist yet")

            controller.apply(create(text = mapOf("a" to "late")))

            assertNotNull(controller.snapshot(surface), "A surface that arrives after the renderer mounted")
            assertEquals("late", (components()["a"] as UiNode.Text).value)
        }

    @Test
    fun writingThroughTheControllerUpdatesTheDataModel() =
        runTest {
            controller.apply(create(text = mapOf("a" to "one")))
            controller.write(surface, UiPath.parse("/name"), JsonPrimitive("Bob"))
            val value = controller.value(surface, UiPath.parse("/name")).first()
            assertEquals("Bob", (assertNotNull(value) as JsonPrimitive).content)
        }

    @Test
    fun aComponentRemovedFromTheSurfaceStopsHoldingItsNode() =
        runTest {
            controller.apply(create(text = mapOf("a" to "one")))
            assertNotNull(components()["a"])

            controller.apply(UiEvent.DeleteSurface(surface))

            assertNull(controller.snapshot(surface))
            assertTrue(components().isEmpty(), "A deleted surface keeps nothing: ${components()}")
        }

    // ─── Lifetime ─────────────────────────────────────────────────────────

    @Test
    fun theOldestSurfaceIsForgottenOnceTheBoundIsPassed() {
        val total = SurfaceController.MAX_OPEN_SURFACES + 3
        repeat(total) { index ->
            controller.apply(
                UiEvent.CreateSurface(
                    SurfaceId("s$index"),
                    NodeRef("a"),
                    mapOf("a" to UiNode.Text("message $index")),
                ),
            )
        }

        val open: Int = controller.surfaces.value.size
        assertEquals(SurfaceController.MAX_OPEN_SURFACES, open, "A conversation is not unbounded")
        assertTrue(controller.snapshot(SurfaceId("s0")) == null, "The oldest is the one that goes")
        assertTrue(controller.snapshot(SurfaceId("s${total - 1}")) != null, "The newest survives")
    }

    @Test
    fun pruningIsOffTheTableWhileTheConversationFits() {
        repeat(SurfaceController.MAX_OPEN_SURFACES) { index ->
            controller.apply(
                UiEvent.CreateSurface(SurfaceId("s$index"), NodeRef("a"), mapOf("a" to UiNode.Text("x"))),
            )
        }
        assertEquals(SurfaceController.MAX_OPEN_SURFACES, controller.surfaces.value.size)
        assertTrue(controller.snapshot(SurfaceId("s0")) != null, "Nothing is dropped without a reason")
    }

    @Test
    fun pruningDropsExactlyTheExcess() {
        controller.prune(maxSurfaces = 2)
        repeat(5) { index ->
            controller.apply(
                UiEvent.CreateSurface(SurfaceId("s$index"), NodeRef("a"), mapOf("a" to UiNode.Text("x"))),
            )
        }
        controller.prune(maxSurfaces = 2)
        assertEquals(2, controller.surfaces.value.size)
    }

    private fun create(text: Map<String, String>): UiEvent.CreateSurface {
        val components: Map<String, UiNode> = text.mapValues { (_, value: String) -> UiNode.Text(value) }
        return UiEvent.CreateSurface(surface, NodeRef(components.keys.first()), components)
    }

    private fun update(text: Map<String, String>): UiEvent.UpdateComponents {
        val components: Map<String, UiNode> = text.mapValues { (_, value: String) -> UiNode.Text(value) }
        return UiEvent.UpdateComponents(surface, components)
    }
}
