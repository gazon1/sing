@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.genui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.resolveText
import com.singularity.todo.feature.genui.surface.ComponentStore
import com.singularity.todo.test.helpers.runGenuiRenderTest
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What one component update costs: how many of the surface's nodes are drawn again.
 *
 * `GenuiSurface` and `RenderSingle` derive the node each of them draws from the surface's
 * component store, instead of collecting a flow per node (#1412). The claim that change rests on
 * is measurable, and this is the measurement: a model's later `updateComponents` that names one
 * component must draw **that** component and leave every other node on screen alone.
 *
 * The count is taken inside the renderer — a `SideEffect` that runs each time a node's renderer
 * is composed — because that is the work a user pays for. A node whose renderer does not run
 * costs nothing, whatever the composition above it does.
 *
 * ## Desktop, not device
 *
 * The render layer is `commonMain`; `GenuiSurface`, `RenderSingle` and `ComponentStore` are the
 * same classes on both platforms. A profiler on a phone would count the same compositions on
 * another JVM, and its numbers could not be re-run by anyone. This measurement runs in the
 * desktop harness, so it is reproducible and can fail a build.
 *
 * ## The control is not optional
 *
 * An assertion that "nothing else changed" passes for the wrong reason when the counter never
 * moves at all. Two things rule that out: the test asserts the changed node *did* draw again, and
 * the second test sends a component update through a node that reads the whole component store —
 * the shape the measurement must be able to see. If that control stops seeing the re-render, the
 * first test proves nothing and says so.
 */
@Tag("slow")
@Tag("slow:compose")
class GenuiRecompositionCountTest {
    @Test
    fun `updating one component re-renders only that component`() {
        val runs = RenderRuns()

        runGenuiRenderTest(
            SIBLING_TEXTS,
            configureRegistry = { registry -> registry.registerCountingText(runs) },
        ) {
            val before: Map<String, Int> = runs.snapshot()
            assertEquals(
                setOf("First", "Second", "Third"),
                before.keys,
                "every node drew on the first composition: $before",
            )

            // One component of the three. The other two are not named, so their nodes are
            // untouched — and a renderer that subscribes to the whole store would draw them again.
            updateComponents(mapOf("b" to UiNode.Text("Second draft")))
            settle()

            assertEquals(
                before.getValue("First"),
                runs.count("First"),
                "an untouched sibling re-rendered: ${runs.snapshot()}",
            )
            assertEquals(
                before.getValue("Third"),
                runs.count("Third"),
                "an untouched sibling re-rendered: ${runs.snapshot()}",
            )
            assertEquals(
                before.getValue("Second"),
                runs.count("Second"),
                "the replaced text was drawn again: ${runs.snapshot()}",
            )
            assertTrue(
                runs.count("Second draft") > 0,
                "the component that changed was not drawn: ${runs.snapshot()}",
            )
        }
    }

    /**
     * The control: a node that reads the whole component store re-renders when a sibling changes.
     *
     * This is the cost the derived node removes, written out as a composable. It proves the count
     * distinguishes the two shapes — without it, the first test would pass equally against a
     * counter that never moves.
     *
     * `collectAsState` rather than `collectAsStateWithLifecycle`: the subscription shape is what
     * is under test, and this test mounts no lifecycle owner to hand the lifecycle-aware
     * collection.
     */
    @Test
    fun `a node that reads the whole component store re-renders when a sibling changes`() =
        runIsolatedComposeTest {
            val store =
                ComponentStore(
                    mapOf(
                        "a" to UiNode.Text("First"),
                        "b" to UiNode.Text("Second"),
                    ),
                )
            val runs = RenderRuns()

            setContent {
                WholeStoreNode("a", store, runs)
                WholeStoreNode("b", store, runs)
            }
            waitForIdle()

            val before: Map<String, Int> = runs.snapshot()
            assertEquals(
                setOf("First", "Second"),
                before.keys,
                "every node drew on the first composition: $before",
            )

            store.put(mapOf("b" to UiNode.Text("Second draft")))
            waitForIdle()

            assertEquals(
                before.getValue("First") + 1,
                runs.count("First"),
                "a node subscribing to the whole store re-renders on a sibling's change — " +
                    "the measurement must be able to see that: ${runs.snapshot()}",
            )
            assertTrue(
                runs.count("Second draft") > 0,
                "the changed node was not drawn: ${runs.snapshot()}",
            )
        }
}

/** One drawing of a surface: the `column` root with three texts, each with its own child id. */
private val SIBLING_TEXTS: List<String> =
    listOf(
        """{"createSurface":{"surfaceId":"s1","rootId":"root","components":[""" +
            """{"id":"root","kind":"column","children":["a","b","c"]},""" +
            """{"id":"a","kind":"text","value":"First"},""" +
            """{"id":"b","kind":"text","value":"Second"},""" +
            """{"id":"c","kind":"text","value":"Third"}]}}""",
    )

/**
 * Replaces the `text` renderer with one that counts, and leaves every other renderer the
 * application's own — including the `column` that dispatches through `RenderSingle`, which is the
 * code under measurement.
 *
 * The text is resolved the way the production renderer resolves it, so the counter keys on what
 * the user would read rather than on the raw template. That is also why the fixture's texts are
 * distinct: two nodes reading the same string would arrive as one count.
 */
private fun ComponentRegistry.registerCountingText(runs: RenderRuns) {
    register("text") { node, ctx, _ ->
        val text: String = ctx.resolveText((node as UiNode.Text).value)
        SideEffect { runs.record(text) }
        BasicText(text)
    }
}

/**
 * A node that subscribes to the whole component map — the shape a derived node replaces.
 *
 * The value is read in the composable's own body, not inside `derivedStateOf`, so every write to
 * the map invalidates this node whether or not its own component changed.
 */
@Composable
private fun WholeStoreNode(
    id: String,
    store: ComponentStore,
    runs: RenderRuns,
) {
    val components: Map<String, UiNode> by store.all.collectAsState()
    val text: String = (components[id] as? UiNode.Text)?.value ?: return
    runs.record(text)
    BasicText(text)
}

/**
 * How many times each rendered text was drawn.
 *
 * A class rather than the map itself: a `@Composable` may not take a raw collection as a
 * parameter (the stability rules this repository enforces), and a counter handed to a composable
 * is exactly such a parameter.
 */
private class RenderRuns {
    private val counts: MutableMap<String, Int> = mutableMapOf()

    fun record(text: String) {
        counts[text] = count(text) + 1
    }

    fun count(text: String): Int = counts[text] ?: 0

    fun snapshot(): Map<String, Int> = counts.toMap()
}
