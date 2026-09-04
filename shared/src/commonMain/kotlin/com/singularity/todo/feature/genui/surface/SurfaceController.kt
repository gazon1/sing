package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.schema.DataModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the lifecycle of all active GenUI surfaces.
 *
 * The single entry point for state mutations is [apply]:
 * call it with events from [com.singularity.todo.feature.genui.parser.A2uiParser]
 * and the reactive [surfaces] [StateFlow] will emit the updated map.
 *
 * **Pure-ish**: the only side effect is updating the internal [MutableStateFlow].
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
            is UiEvent.ParseError -> { /* logging-only, no state change */ }
        }
    }

    private fun createSurface(event: UiEvent.CreateSurface) {
        val surface = Surface(
            id = event.surfaceId,
            rootId = event.rootId,
            components = event.components,
            dataModel = DataModel(),
        )
        _surfaces.value += (event.surfaceId to surface)
    }

    private fun updateComponents(event: UiEvent.UpdateComponents) {
        val existing = _surfaces.value[event.surfaceId] ?: return
        val updated = existing.copy(
            components = existing.components + event.components,
        )
        _surfaces.value += (event.surfaceId to updated)
    }

    private fun updateData(event: UiEvent.UpdateData) {
        val existing = _surfaces.value[event.surfaceId] ?: return
        existing.dataModel.set(event.path, event.value)
        // DataModel.mutableStateFlow is internal; we need to trigger a new emission
        // by replacing the surface reference with an identical copy
        _surfaces.value += (event.surfaceId to existing)
    }

    private fun deleteSurface(event: UiEvent.DeleteSurface) {
        _surfaces.value -= event.surfaceId
    }

    /** Returns the current snapshot of a surface, or null if not found. */
    fun snapshot(surfaceId: SurfaceId): Surface? = _surfaces.value[surfaceId]

    /** Returns the root [UiNode] of a surface, or null if not found. */
    fun rootNode(surfaceId: SurfaceId): UiNode? {
        val s = _surfaces.value[surfaceId] ?: return null
        return s.components[s.rootId.id]
    }

    /** Clears all surfaces (useful for testing). */
    fun reset() {
        _surfaces.value = emptyMap()
    }
}
