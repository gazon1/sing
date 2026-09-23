package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Default runtime context backed by a [SurfaceController].
 *
 * @param surfaceId which surface to render
 * @param controller the surface controller
 * @param registry the component registry for dispatching child node renders
 * @param onAction called when user taps an action button
 * @param onDataChange called when user edits a form field
 */
class DefaultDataContext(
    val surfaceId: SurfaceId,
    private val controller: SurfaceController,
    val registry: ComponentRegistry,
    val onAction: (SurfaceId, String, JsonObject?) -> Unit,
    val onDataChange: (SurfaceId, UiPath, JsonElement) -> Unit,
) {
    val surfaces: StateFlow<Map<SurfaceId, Surface>> = controller.surfaces
}

/** Type alias for [DefaultDataContext]. */
typealias DataContext = DefaultDataContext

/**
 * Creates a [DefaultDataContext] for the given [surfaceId], backed by [controller].
 *
 * Stored in a [remember] so the same context instance is reused across
 * recompositions of the same surface.
 */
@Composable
fun rememberDataContext(
    surfaceId: SurfaceId,
    controller: SurfaceController,
    registry: ComponentRegistry,
    onAction: (SurfaceId, String, JsonObject?) -> Unit,
    onDataChange: (SurfaceId, UiPath, JsonElement) -> Unit,
): DefaultDataContext = remember(surfaceId, controller, registry, onAction, onDataChange) {
    DefaultDataContext(
        surfaceId = surfaceId,
        controller = controller,
        registry = registry,
        onAction = onAction,
        onDataChange = onDataChange,
    )
}
