package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonObject

/**
 * Decoders for the components that draw something on their own: text, controls and inputs.
 *
 * Split from the layout and domain groups because these are the ones a model's answer is mostly
 * made of, and each is short enough to read without knowing the others exist. Every one of them
 * reports a missing required property by name — "heading requires 'text'" is a correction a model
 * can act on, and a decoder that returned a default instead would render a blank screen the model
 * has no way to learn about.
 */
internal object A2uiAtomDecoders {

    fun text(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val value: String = component["value"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "text", "value", pointer, surfaceId))
        return Decode.Ok(UiNode.Text(value, component.tone()))
    }

    fun heading(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val text: String = component["text"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "heading", "text", pointer, surfaceId))
        return Decode.Ok(UiNode.Heading(text, component.optionalInt("level") ?: 2))
    }

    fun badge(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val text: String = component["text"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "badge", "text", pointer, surfaceId))
        return Decode.Ok(UiNode.Badge(text, enumOf(component["tone"], UiNode.Tone.Default)))
    }

    fun icon(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val name: String = component["name"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "icon", "name", pointer, surfaceId))
        return Decode.Ok(UiNode.Icon(name))
    }

    fun button(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val label: String = component["label"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "button", "label", pointer, surfaceId))
        return Decode.Ok(
            UiNode.Button(
                label = label,
                action = component["action"].asStringOrNull(),
                data = component["data"] as? kotlinx.serialization.json.JsonObject,
            ),
        )
    }

    fun textField(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val label: String = component["label"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "text_field", "label", pointer, surfaceId))
        val path: String = component["path"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "text_field", "path", pointer, surfaceId))
        return Decode.Ok(
            UiNode.TextField(
                label = label,
                path = UiPath.parse(path),
                initial = component["initial"].asStringOrNull() ?: "",
            ),
        )
    }

    fun checkbox(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val label: String = component["label"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "checkbox", "label", pointer, surfaceId))
        val path: String = component["path"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "checkbox", "path", pointer, surfaceId))
        return Decode.Ok(
            UiNode.Checkbox(
                label = label,
                path = UiPath.parse(path),
                initial = component.optionalBoolean("initial") ?: false,
            ),
        )
    }
}
