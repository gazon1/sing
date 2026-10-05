package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonObject

/**
 * Decoders for the components that name the app's own nouns.
 *
 * They read the surface's data model and nothing else — a component that could fetch would make a
 * screen's content depend on when it was drawn, and a surface has to be reproducible from its
 * messages alone.
 *
 * Two of the three accept *either* a literal or a bound path. That is deliberate: `due_date` with
 * `value` is correct for a screen built once and wrong for a follow-up that only patches one field,
 * and making the model choose per surface is a rule it will get wrong in both directions. Accepting
 * both, and rejecting neither, is the smaller rule.
 */
internal object A2uiDomainDecoders {

    fun taskCard(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val title: String = component["title"].asStringOrNull()
            ?: return Decode.Bad(errors.missingProperty(id, "task_card", "title", pointer, surfaceId))
        return Decode.Ok(
            UiNode.TaskCard(
                title = title,
                duePath = component["duePath"].asStringOrNull()?.let { UiPath.parse(it) },
                projectPath = component["projectPath"].asStringOrNull()?.let { UiPath.parse(it) },
                done = component.optionalBoolean("done") ?: false,
                action = component["action"].asStringOrNull(),
            ),
        )
    }

    fun dueDate(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val path: String? = component["path"].asStringOrNull()
        val literal: String? = component["value"].asStringOrNull()
        if (path == null && literal == null) {
            return Decode.Bad(errors.eitherProperty(id, "due_date", "path", "value", pointer, surfaceId))
        }
        return Decode.Ok(
            UiNode.DueDate(
                path = path?.let { UiPath.parse(it) },
                value = literal,
                style = enumOf(component["style"], UiNode.DueStyle.Relative),
            ),
        )
    }

    fun projectChip(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
        errors: A2uiComponentErrors,
    ): Decode {
        val name: String? = component["name"].asStringOrNull()
        val path: String? = component["path"].asStringOrNull()
        if (name == null && path == null) {
            return Decode.Bad(errors.eitherProperty(id, "project_chip", "name", "path", pointer, surfaceId))
        }
        return Decode.Ok(
            UiNode.ProjectChip(
                name = name,
                path = path?.let { UiPath.parse(it) },
                tone = component.tone(),
            ),
        )
    }
}
