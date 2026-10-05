package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * Events emitted by [A2uiParser] after processing a stream of A2UI JSON-Lines.
 * These are the only events that [com.singularity.todo.feature.genui.surface.SurfaceController] handles.
 *
 * There is no parse-failure event here on purpose. A failure used to be a variant of this
 * hierarchy, so a rejected message was a thing the controller was handed and did nothing with; it
 * is now reported as
 * [com.singularity.todo.feature.genui.core.A2uiError] alongside the events that did apply, which
 * is what lets a caller both apply the good part and tell the model about the rest.
 *
 * [schemaVersion] is the schema version of this event. Events with a schema version
 * newer than [A2uiParser.A2UI_CURRENT_SCHEMA_VERSION] are rejected by the parser.
 */
sealed interface UiEvent {
    /** Schema version of this event. */
    val schemaVersion: Int

    /**
     * This event, addressed to [surfaceId].
     *
     * The identifier inside a message is chosen by a model and is only meaningful inside the answer
     * that produced it: it is how `createSurface` and the `updateData` that follows are tied
     * together. Which answer a surface belongs to is not the model's decision — it is the caller's,
     * because a message in the conversation points at a surface for as long as the conversation can
     * be scrolled back through. Letting the model name that would put two answers' screens at one
     * address, and the older message would redraw whatever arrived last.
     */
    fun retargeted(surfaceId: SurfaceId): UiEvent

    /** Creates a new surface with an initial set of components. */
    data class CreateSurface(
        val surfaceId: SurfaceId,
        val rootId: NodeRef,
        val components: Map<String, UiNode>,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent {
        override fun retargeted(surfaceId: SurfaceId): UiEvent = copy(surfaceId = surfaceId)
    }

    /** Adds or updates components within an existing surface. */
    data class UpdateComponents(
        val surfaceId: SurfaceId,
        val components: Map<String, UiNode>,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent {
        override fun retargeted(surfaceId: SurfaceId): UiEvent = copy(surfaceId = surfaceId)
    }

    /** Updates data values in the surface's data model. */
    data class UpdateData(
        val surfaceId: SurfaceId,
        val path: UiPath,
        val value: kotlinx.serialization.json.JsonElement,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent {
        override fun retargeted(surfaceId: SurfaceId): UiEvent = copy(surfaceId = surfaceId)
    }

    /** Deletes an entire surface. */
    data class DeleteSurface(
        val surfaceId: SurfaceId,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent {
        override fun retargeted(surfaceId: SurfaceId): UiEvent = copy(surfaceId = surfaceId)
    }
}
