package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * Events emitted by [A2uiParser] after processing a stream of A2UI v0.9 JSON lines.
 * These are the only events that [com.singularity.todo.feature.genui.surface.SurfaceController] handles.
 */
sealed interface UiEvent {

    /** Creates a new surface with an initial set of components. */
    data class CreateSurface(
        val surfaceId: SurfaceId,
        val rootId: NodeRef,
        val components: Map<String, UiNode>,
    ) : UiEvent

    /** Adds or updates components within an existing surface. */
    data class UpdateComponents(
        val surfaceId: SurfaceId,
        val components: Map<String, UiNode>,
    ) : UiEvent

    /** Updates data values in the surface's data model. */
    data class UpdateData(
        val surfaceId: SurfaceId,
        val path: UiPath,
        val value: kotlinx.serialization.json.JsonElement,
    ) : UiEvent

    /** Deletes an entire surface. */
    data class DeleteSurface(
        val surfaceId: SurfaceId,
    ) : UiEvent

    /** An error occurred while parsing — contains the raw invalid input. */
    data class ParseError(
        val input: String,
        val message: String,
    ) : UiEvent
}
