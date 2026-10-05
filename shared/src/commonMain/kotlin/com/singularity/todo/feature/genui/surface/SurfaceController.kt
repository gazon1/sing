package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Owns every open surface, and is the only thing allowed to change one.
 *
 * The two observations a renderer needs are offered per component and per data path, not as the
 * whole surface. Both re-derive when the set of surfaces changes — which is rare, once per surface
 * lifecycle — and are conflated within, so a burst of streaming messages costs one recomposition
 * rather than one per message.
 */
class SurfaceController {

    private val _surfaces = MutableStateFlow<Map<SurfaceId, Surface>>(emptyMap())
    val surfaces: StateFlow<Map<SurfaceId, Surface>> = _surfaces.asStateFlow()

    /**
     * Applies a single [UiEvent] to the current state.
     * All other overloads delegate to this one.
     */
    fun apply(event: UiEvent) {
        when (event) {
            is UiEvent.CreateSurface -> createSurface(event)
            is UiEvent.UpdateComponents -> updateComponents(event)
            is UiEvent.UpdateData -> updateData(event)
            is UiEvent.DeleteSurface -> deleteSurface(event)
        }
    }

    private fun createSurface(event: UiEvent.CreateSurface) {
        _surfaces.value += (event.surfaceId to Surface(event.surfaceId, event.rootId, event.components))
        prune()
    }

    private fun updateComponents(event: UiEvent.UpdateComponents) {
        // Replaced through the store rather than by copying the surface, so a subscriber to one
        // component is not woken by a change to another.
        _surfaces.value[event.surfaceId]?.componentStore?.put(event.components)
    }

    private fun updateData(event: UiEvent.UpdateData) {
        // The data model is itself a StateFlow, so writing to it emits to exactly the paths that
        // read it. Nothing has to be re-published to make the write visible, which is what the
        // copy-on-write workaround here used to do.
        _surfaces.value[event.surfaceId]?.dataModel?.set(event.path, event.value)
    }

    private fun deleteSurface(event: UiEvent.DeleteSurface) {
        _surfaces.value -= event.surfaceId
    }

    /** Returns the current snapshot of a surface, or null if not found. */
    fun snapshot(surfaceId: SurfaceId): Surface? = _surfaces.value[surfaceId]

    /** Returns the root [UiNode] of a surface, or null if not found. */
    fun rootNode(surfaceId: SurfaceId): UiNode? {
        val surface: Surface = _surfaces.value[surfaceId] ?: return null
        return surface.components[surface.rootId.id]
    }

    /** Writes a value into a surface's data model, the counterpart of [value]. */
    fun write(surfaceId: SurfaceId, path: UiPath, value: JsonElement) {
        _surfaces.value[surfaceId]?.dataModel?.set(path, value)
    }

    /** Clears all surfaces (useful for testing, and before a fresh turn of a conversation). */
    fun reset() {
        _surfaces.value = emptyMap()
    }

    /**
     * Forgets the oldest surface once more than [maxSurfaces] are open.
     *
     * Surfaces are never closed by their owner — a message can be scrolled back to at any time — so
     * without a bound they accumulate for the life of the process, each holding its own data model.
     * A conversation is not unbounded, and neither is what a user scrolls back through.
     */
    fun prune(maxSurfaces: Int = MAX_OPEN_SURFACES) {
        if (_surfaces.value.size <= maxSurfaces) return
        val excess: Int = _surfaces.value.size - maxSurfaces
        val oldest: Set<SurfaceId> = _surfaces.value.keys.take(excess).toSet()
        _surfaces.value -= oldest
    }

    companion object {
        /** Roughly a long conversation's worth of screens; past it the oldest are dropped. */
        const val MAX_OPEN_SURFACES: Int = 24
    }
}
