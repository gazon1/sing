package com.singularity.todo.feature.genui.render

import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Default [DataContext] implementation backed by a [SurfaceController].
 *
 * @param surfaceId which surface to render
 * @param controller the surface controller
 * @param registry the component registry for dispatching child node renders
 * @param onAction called when user taps an action button
 * @param onDataChange called when user edits a form field
 */
class DefaultDataContext(
    override val surfaceId: SurfaceId,
    private val controller: SurfaceController,
    override val registry: ComponentRegistry,
    override val onAction: (SurfaceId, String, JsonObject?) -> Unit,
    override val onDataChange: (SurfaceId, UiPath, JsonElement) -> Unit,
) : DataContext {
    override val surfaces: StateFlow<Map<SurfaceId, Surface>> = controller.surfaces
}
