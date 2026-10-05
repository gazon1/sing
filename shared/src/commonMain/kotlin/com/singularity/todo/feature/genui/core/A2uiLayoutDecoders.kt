package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Decoders for the components that arrange other components.
 *
 * Separate from the atoms because these are the ones with a structural rule attached — how many
 * children, whether a placement is allowed — and a reader looking for "what can hold what" should
 * not have to read a text field to find it. The rule itself is declared in the catalog and checked
 * before these run; what is here is turning the wire shape into the node.
 */
internal object A2uiLayoutDecoders {

    fun card(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val child: String = component["child"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "card", "child", pointer, surfaceId))
        return Decode.Ok(UiNode.Card(NodeRef(child)))
    }

    fun modal(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val child: String = component["child"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "modal", "child", pointer, surfaceId))
        val openPath: String = component["openPath"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "modal", "openPath", pointer, surfaceId))
        return Decode.Ok(UiNode.Modal(NodeRef(child), UiPath.parse(openPath)))
    }

    /**
     * Tab entries are all-or-nothing.
     *
     * A partly readable entry is dropped rather than guessed at, and an array that yields no usable
     * entry at all is a rejection rather than an empty tab strip — a surface with a tab bar that
     * opens nothing is worse than one the model is told to fix.
     */
    fun tabs(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val array: JsonArray = component["tabs"] as? JsonArray
            ?: return Decode.Bad(
                errors.mismatch(id, "tabs", "tabs", "an array of {title, child} objects", pointer, surfaceId),
            )
        val parsed: List<UiNode.Tab> = array.mapNotNull { element: JsonElement ->
            val entry: JsonObject = element as? JsonObject ?: return@mapNotNull null
            val title: String = entry["title"].asStringOrNull() ?: return@mapNotNull null
            val child: String = entry["child"].asStringOrNull() ?: return@mapNotNull null
            UiNode.Tab(title, NodeRef(child))
        }
        if (parsed.isEmpty()) {
            return Decode.Bad(
                errors.mismatch(id, "tabs", "tabs", "at least one {title, child} object", pointer, surfaceId),
            )
        }
        return Decode.Ok(UiNode.Tabs(parsed))
    }
}
