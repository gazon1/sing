@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.test.helpers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.DefaultDataContext
import com.singularity.todo.feature.genui.render.GenuiSurface
import com.singularity.todo.feature.genui.render.material3.Material3Catalog
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.test.fakes.FakeClock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * The moment every harness fixture is drawn at: a Monday, mid-morning, far from any boundary a
 * date format could disagree about.
 */
private val FIXED_INSTANT: Instant = Instant.parse("2026-03-02T10:00:00Z")

/** The identifier every harness fixture uses. Two would only hide a retargeting bug. */
val GenuiSurfaceId: SurfaceId = SurfaceId("harness")

/** One press of a control inside a surface, as the layer reports it to the screen. */
data class GenuiPress(val surfaceId: SurfaceId, val name: String, val data: JsonObject?)

/**
 * One edit of a bound field.
 *
 * The value is already in the surface's data model by the time this arrives — it is a notification,
 * not the write, and a read-only surface may ignore it without anything being persisted.
 */
data class GenuiWrite(val surfaceId: SurfaceId, val path: UiPath, val value: JsonElement)

/**
 * Renders a generated surface, for tests that need to know a component draws something.
 *
 * Every other GenUI test stops at "it parsed and it validated". That is not the same as "it is on
 * screen", and the gap is not theoretical: a renderer registered against the right name that draws
 * an empty box passes every store-level test and shows the user a blank card. This is what catches
 * that.
 *
 * The pipeline is the production one all the way down — same parser, same validator, same
 * controller, same registry installation — so passing here is not passing against a stand-in:
 *
 * ```
 * runGenuiRenderTest(TASK_LIST) {
 *     assertHasNode("task_card")
 * }
 * ```
 *
 * @param responses JSON Lines exactly as a model would send them, applied in order.
 * @param assertClean fail when a line is rejected. A test about drawing a screen wants a screen,
 *   not a screen minus whatever the validator disliked.
 */
fun runGenuiRenderTest(
    responses: List<String>,
    assertClean: Boolean = true,
    test: suspend GenuiRenderScope.() -> Unit,
) = runIsolatedComposeTest {
    val controller: SurfaceController = SurfaceController()
    val catalog: A2uiCatalog = SingularityCatalog
    val parser: A2uiParser = A2uiParser(catalog)
    val processor: A2uiMessageProcessor = A2uiMessageProcessor(A2uiValidator(catalog), controller)

    responses.forEachIndexed { index: Int, line: String ->
        val outcome: A2uiParseOutcome = parser.parseLine(line)
        if (outcome !is A2uiParseOutcome.Parsed) {
            throw AssertionError("Line ${index + 1} did not parse: $outcome")
        }
        // Retargeted exactly as GenuiSession does it: the identifier inside a message groups one
        // answer's lines, and the surface is filed under the caller's. A harness that skipped this
        // would put the surface under whatever the fixture happened to name it, and every lookup
        // here would miss.
        val result = processor.apply(outcome.event.retargeted(GenuiSurfaceId), outcome.errors)
        if (assertClean && result.errors.isNotEmpty()) {
            throw AssertionError("Line ${index + 1} was rejected: ${result.errors.map { it.asFeedback() }}")
        }
    }

    val presses: MutableList<GenuiPress> = mutableListOf()
    val writes: MutableList<GenuiWrite> = mutableListOf()
    val registry: ComponentRegistry = ComponentRegistry().also { Material3Catalog.installAll(it) }
    val context = DefaultDataContext(
        surfaceId = GenuiSurfaceId,
        controller = controller,
        registry = registry,
        onAction = { surfaceId, name, data -> presses += GenuiPress(surfaceId, name, data) },
        onDataChange = { surfaceId, path, value -> writes += GenuiWrite(surfaceId, path, value) },
        // A fixed date, so "Today" and `formatRelative` mean something the test can assert. Reading
        // the wall clock here would make every date-bearing assertion depend on the day it runs.
        clock = FakeClock(FIXED_INSTANT),
    )

    val owner: TestLifecycleOwner = TestLifecycleOwner()
    setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme { GenuiSurface(context) }
        }
    }
    // On the UI thread: LifecycleRegistry refuses any other, and the failure it raises is about
    // threads rather than about the surface under test.
    runOnIdle { owner.resume() }
    waitForIdle()

    GenuiRenderScope(this, controller, presses, writes).test()
}

/**
 * What a render test is allowed to assert on.
 *
 * It owns the running test rather than borrowing one implicitly: every assertion here is about
 * nodes on screen, and a scope that had to be handed the test in order to ask a question about it
 * would be two objects where one reads better.
 */
class GenuiRenderScope(
    private val ui: DesktopComposeUiTest,
    private val controller: SurfaceController,
    /** Every press the surface produced, in order. */
    val presses: List<GenuiPress>,
    /** Every field edit the surface reported, in order. */
    val writes: List<GenuiWrite>,
) {
    /** What the surface's data model holds at [path]. */
    fun valueAt(path: String): JsonElement? =
        controller.snapshot(GenuiSurfaceId)?.dataModel?.get(UiPath.parse(path))

    /**
     * The node a renderer tagged `genui_<name>`.
     *
     * The tag is the hook renderers publish through
     * [com.singularity.todo.feature.genui.render.genuiTag], and it is the only thing that separates
     * "drew a card" from "drew a container of nothing" — a surface of correctly placed empty boxes
     * passes any test that stops at the structure of the semantics tree.
     */
    fun node(name: String): SemanticsNodeInteraction =
        ui.onNodeWithTag(TestTags.genUi(name), useUnmergedTree = true)

    /** As [node], for a tag the fixture built from a value the renderer composed. */
    fun nodeTagged(tag: String): SemanticsNodeInteraction = ui.onNodeWithTag(tag, useUnmergedTree = true)

    /** Every node tagged `genui_<name>` — a list component puts several on screen at once. */
    fun nodes(name: String): SemanticsNodeInteractionCollection =
        ui.onAllNodesWithTag(TestTags.genUi(name), useUnmergedTree = true)

    /** The first of them, for a list whose rows are not yet distinguishable by their contents. */
    fun firstNode(name: String): SemanticsNodeInteraction = nodes(name)[0]

    /** Asserts the node a renderer tagged `genui_<name>` is on screen. */
    fun assertHasNode(name: String) {
        node(name).assertExists()
    }
}

/**
 * A lifecycle that is already resumed.
 *
 * `collectAsStateWithLifecycle` is what every renderer in the layer observes through, and it starts
 * collecting only once the lifecycle reaches STARTED. Without one supplied, a surface renders
 * nothing at all — and the test fails on a missing node rather than on anything it is about, which
 * is the most expensive way to be told that the harness, not the code, is wrong.
 */
private class TestLifecycleOwner : LifecycleOwner {

    private val registry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    /** Moved after setContent, so there is a composition to collect against when it starts. */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}
