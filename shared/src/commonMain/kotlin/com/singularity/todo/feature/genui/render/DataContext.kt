package com.singularity.todo.feature.genui.render

import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Runtime context passed to every rendered [UiNode][com.singularity.todo.feature.genui.catalog.UiNode].
 *
 * Provides access to the current [Surface], the [SurfaceController] for reads,
 * callbacks for user interactions, and the [ComponentRegistry] for dispatching
 * recursive renders.
 */
interface DataContext {
    val surfaceId: SurfaceId
    /** Registry used to dispatch rendering of child nodes. */
    val registry: ComponentRegistry
    val surfaces: StateFlow<Map<SurfaceId, Surface>>
    val onAction: (surfaceId: SurfaceId, action: String, data: JsonObject?) -> Unit
    val onDataChange: (surfaceId: SurfaceId, path: UiPath, value: JsonElement) -> Unit
}
