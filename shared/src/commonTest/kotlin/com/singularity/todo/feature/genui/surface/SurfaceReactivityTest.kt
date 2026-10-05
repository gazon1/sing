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
 * Per-component observation.
 *
 * The behaviour under test is the one the layer did not have: an update to one component must not
 * wake a subscriber watching another. When components were an immutable map inside an immutable
 * surface, one update replaced the map and every node in the tree re-rendered — which is why
 * extending the catalogue was expensive.
 */
@Tag("fast")
class SurfaceReactivityTest {

    private val controller = SurfaceController()
    private val surface = SurfaceId("s1")

    @Test
    fun updatingOneComponentDoesNotEmitOnAnother() = runTest {
        controller.apply(create(text = mapOf("a" to "one", "b" to "two")))
        val seen: MutableList<UiNode?> = mutableListOf()
        val job = launch { controller.component(surface, "b").collect { seen += it } }
        testScheduler.runCurrent()

        controller.apply(update(text = mapOf("a" to "changed")))
        testScheduler.runCurrent()

        assertEquals(1, seen.size, "The unrelated component emitted: $seen")
        job.cancel()
    }

    @Test
    fun updatingTheComponentEmits() = runTest {
        controller.apply(create(text = mapOf("a" to "one", "b" to "two")))
        val seen: MutableList<UiNode?> = mutableListOf()
        val job = launch { controller.component(surface, "a").collect { seen += it } }
        testScheduler.runCurrent()

        controller.apply(update(text = mapOf("a" to "changed")))
        testScheduler.runCurrent()

        assertEquals(2, seen.size, "The changed component emitted: $seen")
        assertEquals("changed", (seen.last() as UiNode.Text).value)
        job.cancel()
    }

    @Test
    fun rewritingAnIdenticalComponentDoesNotEmit() = runTest {
        controller.apply(create(text = mapOf("a" to "same")))
        val seen: MutableList<UiNode?> = mutableListOf()
        val job = launch { controller.component(surface, "a").collect { seen += it } }
        testScheduler.runCurrent()

        controller.apply(update(text = mapOf("a" to "same")))
        testScheduler.runCurrent()

        assertEquals(1, seen.size, "A no-op update is not a change: $seen")
        job.cancel()
    }

    @Test
    fun aDataUpdateEmitsOnlyOnThePathThatChanged() = runTest {
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
    fun aSurfaceAppearingLaterStartsEmitting() = runTest {
        val seen: MutableList<UiNode?> = mutableListOf()
        val job = launch { controller.component(surface, "a").collect { seen += it } }
        testScheduler.runCurrent()
        assertTrue(seen.isEmpty())

        controller.apply(create(text = mapOf("a" to "late")))
        testScheduler.runCurrent()
        assertEquals(1, seen.size, "A component that arrives after the collector started: $seen")
        job.cancel()
    }

    @Test
    fun writingThroughTheControllerUpdatesTheDataModel() = runTest {
        controller.apply(create(text = mapOf("a" to "one")))
        controller.write(surface, UiPath.parse("/name"), JsonPrimitive("Bob"))
        val value = controller.value(surface, UiPath.parse("/name")).first()
        assertEquals("Bob", (assertNotNull(value) as JsonPrimitive).content)
    }

    @Test
    fun aComponentRemovedFromTheSurfaceStopsEmittingItsNode() = runTest {
        controller.apply(create(text = mapOf("a" to "one")))
        val seen: MutableList<UiNode?> = mutableListOf()
        val job = launch { controller.component(surface, "a").collect { seen += it } }
        testScheduler.runCurrent()

        controller.apply(UiEvent.DeleteSurface(surface))
        testScheduler.runCurrent()

        assertNull(controller.snapshot(surface))
        job.cancel()
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
