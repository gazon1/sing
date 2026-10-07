package com.singularity.todo.feature.genui.parser

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.core.A2uiError
import com.singularity.todo.feature.genui.core.A2uiErrorCode
import com.singularity.todo.feature.genui.core.A2uiNodeFactory
import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.core.A2uiSeverity
import com.singularity.todo.feature.genui.core.GenuiUsageCounter
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns the body of a recognised message into an event.
 *
 * Split from [A2uiParser] because the two fail differently and only one of them is about the
 * envelope: this one answers "is this a well-formed `createSurface`?", the other answers "is this
 * line a message at all?". Keeping them together made the envelope's guarantees the first thing
 * anyone had to read to learn what a valid body looks like.
 *
 * Component-level validation belongs to [A2uiNodeFactory], which reads the catalog — so a property
 * this class insists on is a property the *operation* needs, not one a component declares.
 */
internal class A2uiMessageDecoder(catalog: A2uiCatalog, private val usage: GenuiUsageCounter = GenuiUsageCounter()) {

    private val factory: A2uiNodeFactory = A2uiNodeFactory(catalog, usage)

    /** The running tally of which kinds a model actually draws. */
    fun usageCounter(): GenuiUsageCounter = factory.usageCounter()

    fun decode(operation: String, body: JsonObject): A2uiParseOutcome = when (operation) {
        "createSurface" -> createSurface(body)
        "updateComponents" -> updateComponents(body)
        "updateData" -> updateData(body)
        else -> deleteSurface(body)
    }

    private fun createSurface(body: JsonObject): A2uiParseOutcome {
        val surfaceId: SurfaceId = body.surfaceId() ?: return missing("/createSurface/surfaceId")
        val rootId: String = body.string("rootId") ?: return missing("/createSurface/rootId")
        val array: JsonArray = body["components"] as? JsonArray
            ?: return missing("/createSurface/components")
        val parsed = factory.parseComponents(array, surfaceId, "/createSurface/components")
        return A2uiParseOutcome.Parsed(
            event = UiEvent.CreateSurface(
                surfaceId = surfaceId,
                rootId = NodeRef(rootId),
                components = parsed.components,
                schemaVersion = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
            ),
            errors = parsed.errors,
        )
    }

    private fun updateComponents(body: JsonObject): A2uiParseOutcome {
        val surfaceId: SurfaceId = body.surfaceId() ?: return missing("/updateComponents/surfaceId")
        val array: JsonArray = body["components"] as? JsonArray
            ?: return missing("/updateComponents/components")
        val parsed = factory.parseComponents(array, surfaceId, "/updateComponents/components")
        return A2uiParseOutcome.Parsed(
            event = UiEvent.UpdateComponents(
                surfaceId = surfaceId,
                components = parsed.components,
                schemaVersion = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
            ),
            errors = parsed.errors,
        )
    }

    private fun updateData(body: JsonObject): A2uiParseOutcome {
        val surfaceId: SurfaceId = body.surfaceId() ?: return missing("/updateData/surfaceId")
        val path: String = body.string("path") ?: return missing("/updateData/path")
        // `null` is a legitimate value to write, so this checks presence rather than usefulness —
        // a path written to clear a value is how a surface says "no longer known".
        val value: JsonElement = body["value"] ?: return missing("/updateData/value")
        return A2uiParseOutcome.Parsed(
            event = UiEvent.UpdateData(
                surfaceId = surfaceId,
                path = UiPath.parse(path),
                value = value,
                schemaVersion = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
            ),
        )
    }

    private fun deleteSurface(body: JsonObject): A2uiParseOutcome {
        val surfaceId: SurfaceId = body.surfaceId() ?: return missing("/deleteSurface/surfaceId")
        return A2uiParseOutcome.Parsed(
            event = UiEvent.DeleteSurface(
                surfaceId = surfaceId,
                schemaVersion = A2uiParser.A2UI_CURRENT_SCHEMA_VERSION,
            ),
        )
    }

    private fun missing(pointer: String): A2uiParseOutcome.Failed = A2uiParseOutcome.Failed(
        A2uiError(
            code = A2uiErrorCode.MALFORMED_LINE,
            message = "Required value at '$pointer' is missing or unusable.",
            pointer = pointer,
            severity = A2uiSeverity.MESSAGE,
        ),
    )

    private fun JsonObject.surfaceId(): SurfaceId? = string("surfaceId")?.let { SurfaceId(it) }

    /** A JSON string, or null — a number where a string belongs is not coerced. */
    private fun JsonObject.string(name: String): String? = when (val element: JsonElement? = this[name]) {
        is JsonPrimitive -> if (element.isString) element.content else null
        else -> null
    }
}
