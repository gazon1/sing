package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * Events emitted by [A2uiParser] after processing a stream of A2UI JSON-Lines.
 * These are the only events that [com.singularity.todo.feature.genui.surface.SurfaceController] handles.
 *
 * [schemaVersion] is the schema version of this event. Events with a schema version
 * newer than [A2uiParser.A2UI_CURRENT_SCHEMA_VERSION] are skipped by the parser.
 */
sealed interface UiEvent {
    /** Schema version of this event. */
    val schemaVersion: Int

    /** Creates a new surface with an initial set of components. */
    data class CreateSurface(
        val surfaceId: SurfaceId,
        val rootId: NodeRef,
        val components: Map<String, UiNode>,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent

    /** Adds or updates components within an existing surface. */
    data class UpdateComponents(
        val surfaceId: SurfaceId,
        val components: Map<String, UiNode>,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent

    /** Updates data values in the surface's data model. */
    data class UpdateData(
        val surfaceId: SurfaceId,
        val path: UiPath,
        val value: kotlinx.serialization.json.JsonElement,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent

    /** Deletes an entire surface. */
    data class DeleteSurface(
        val surfaceId: SurfaceId,
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
    ) : UiEvent

    /** An error occurred while parsing — contains the raw invalid input. */
    data class ParseError(val input: String, val message: String) : UiEvent {
        override val schemaVersion: Int = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION
    }
}
