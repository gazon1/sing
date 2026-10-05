package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.DataModel
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.component
import com.singularity.todo.feature.genui.surface.rootRef
import com.singularity.todo.feature.genui.surface.value
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Everything a renderer is allowed to reach: the components, the data they bind to, and the two
 * callbacks that leave the layer.
 *
 * The component and data observations are per identifier, not per surface. A renderer that
 * subscribes to the whole surface cannot avoid re-rendering when any part of it changes, and a
 * tree of containers multiplies that cost by the number of nodes.
 *
 * @param clock the clock renderers read "today" from. A card that says "Today" and a template
 *   that calls `formatRelative` both need the date, and neither should read it from the system:
 *   a surface that cannot be rendered for a known date cannot be tested against one, and a due
 *   chip that reads the wall clock at draw time is a component whose test has to be written
 *   around the day it happens to run.
 * @param onAction called when the user activates a control
 * @param onDataChange called when the user edits a form field. The value is already written to the
 *   surface's data model by then; this is a notification, not the write itself, so a read-only
 *   surface can ignore it and nothing is persisted.
 */
class DefaultDataContext(
    val surfaceId: SurfaceId,
    private val controller: SurfaceController,
    val registry: ComponentRegistry,
    val onAction: (SurfaceId, String, JsonObject?) -> Unit,
    val onDataChange: (SurfaceId, UiPath, JsonElement) -> Unit,
    val clock: Clock,
) {
    /** The whole surface map, for callers that need to know what exists rather than what changed. */
    val surfaces: StateFlow<Map<SurfaceId, Surface>> = controller.surfaces

    /**
     * The data model backing this surface, or null while the surface does not exist.
     *
     * Exposed for template resolution, which reads paths outside a reactive subscription: a title
     * is re-read whenever the component re-renders, not whenever one of the values it mentions
     * changes, and subscribing each string to every path it could mention is not a cost worth
     * paying for the handful of strings a surface contains.
     */
    val dataModel: DataModel? get() = controller.snapshot(surfaceId)?.dataModel

    /** One component, emitting only when that component changes. */
    fun component(id: String): Flow<UiNode?> = controller.component(surfaceId, id)

    /** The root component reference of this surface. */
    fun rootRef(): Flow<NodeRef?> = controller.rootRef(surfaceId)

    /** The value at a data-model path, emitting only when that value changes. */
    fun value(path: UiPath): Flow<JsonElement?> = controller.value(surfaceId, path)

    /**
     * Writes a value and announces it.
     *
     * The write happens here rather than in the callback so that a field reflects its own edit
     * immediately and so that every bound path agrees, whatever the screen does with the
     * notification afterwards.
     */
    fun write(path: UiPath, value: JsonElement) {
        controller.write(surfaceId, path, value)
        onDataChange(surfaceId, path, value)
    }
}

/** Type alias for [DefaultDataContext]. */
typealias DataContext = DefaultDataContext

/**
 * Creates a [DefaultDataContext] for the given [surfaceId], backed by [controller].
 *
 * Stored in [remember] so the same context instance is reused across recompositions of the same
 * surface.
 */
@Composable
fun rememberDataContext(
    surfaceId: SurfaceId,
    controller: SurfaceController,
    registry: ComponentRegistry,
    onAction: (SurfaceId, String, JsonObject?) -> Unit,
    onDataChange: (SurfaceId, UiPath, JsonElement) -> Unit,
    clock: Clock,
): DefaultDataContext = remember(surfaceId, controller, registry, onAction, onDataChange, clock) {
    DefaultDataContext(
        surfaceId = surfaceId,
        controller = controller,
        registry = registry,
        onAction = onAction,
        onDataChange = onDataChange,
        clock = clock,
    )
}
