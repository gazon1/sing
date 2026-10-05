package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.A2uiComponentSchema
import com.singularity.todo.feature.genui.catalog.A2uiProperty
import com.singularity.todo.feature.genui.catalog.A2uiType
import com.singularity.todo.feature.genui.catalog.ChildSlot
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.function.FormatString
import com.singularity.todo.feature.genui.function.TemplateError
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Components that survived decoding, plus the ones that did not and why. */
internal data class ComponentParse(val components: Map<String, UiNode>, val errors: List<A2uiError>)

/**
 * What one component decoded to: a node, or the reason there is none.
 *
 * A sealed type rather than a nullable node because the two failure modes are not the same: a node
 * with a default in a field the model omitted is a message the model was never shown, and one that
 * it can be told about.
 */
internal sealed interface Decode {
    data class Ok(val node: UiNode) : Decode
    data class Bad(val error: A2uiError) : Decode
}

/**
 * Turns component objects into [UiNode]s, or into a reason why it cannot.
 *
 * The catalog is consulted for what each component may carry, so the property list is declared
 * once and this class does not restate it. What lives here is the mapping from a declared property
 * to the node's own field — the part a new component genuinely adds, and the part that has to be
 * written out.
 *
 * Checking happens here rather than at the renderer because a component rejected at the renderer
 * has already been reported to the model as fine. The renderer is the last place a problem can be
 * found and the worst place to report it from.
 *
 * Every reader is total: a value that is absent, of the wrong type, or unparseable produces an
 * [A2uiError], never an exception and never a silently substituted default. A required property
 * that is missing yields `null` from its reader and is turned into a rejection by the branch that
 * asked for it, which is why the branches read defensively even though the presence check above
 * has already run.
 */
internal class A2uiNodeFactory(
    private val catalog: A2uiCatalog,
    /**
     * What the model reaches for, recorded where a component becomes a node.
     *
     * After the checks and before the dispatch, so that a kind the model wrote and the validator
     * then rejected is counted as a mistake rather than as usage — the tally answers "does this
     * component earn its place", and a component that is only ever written wrongly has not earned
     * anything.
     */
    private val usage: GenuiUsageCounter = GenuiUsageCounter(),
) {

    /**
     * The messages this layer reports about a model's own output.
     *
     * Built once and handed to the decoders, because the rejections are as much a part of the
     * contract as the nodes are: two components failing the same way should say the same thing.
     */
    private val errors: A2uiComponentErrors = A2uiComponentErrors(catalog)

    /** The running tally of which kinds a model actually draws. */
    fun usageCounter(): GenuiUsageCounter = usage

    fun parseComponents(
        array: JsonArray,
        surfaceId: SurfaceId,
        pointerBase: String,
    ): ComponentParse {
        val nodes: MutableMap<String, UiNode> = mutableMapOf()
        val rejections: MutableList<A2uiError> = mutableListOf()

        array.forEachIndexed { index: Int, element: JsonElement ->
            val pointer: String = "$pointerBase/$index"
            val component: JsonObject? = element as? JsonObject
            if (component == null) {
                rejections += errors.malformed("$pointer is not a component object", pointer, surfaceId)
                return@forEachIndexed
            }
            val id: String? = component["id"].asStringOrNull()
            if (id.isNullOrBlank()) {
                rejections += errors.malformed("$pointer has no component id", "$pointer/id", surfaceId)
                return@forEachIndexed
            }
            if (id in nodes) {
                rejections += A2uiError(
                    code = A2uiErrorCode.DUPLICATE_COMPONENT_ID,
                    message = "Component id '$id' is defined twice in one message; the last one applies.",
                    pointer = "$pointer/id",
                    surfaceId = surfaceId,
                    severity = A2uiSeverity.COMPONENT,
                )
            }
            when (val decoded = decode(component, id, pointer, surfaceId)) {
                is Decode.Ok -> nodes[id] = decoded.node
                is Decode.Bad -> rejections += decoded.error
            }
        }
        return ComponentParse(nodes, rejections)
    }

    /**
     * Checks the component against the catalog, then hands it to the decoder for its group.
     *
     * Three checks, in this order, and all three are about the message rather than the component: a
     * component with no kind cannot be looked up, a kind the catalog does not declare cannot be
     * checked, and a property that is missing or of the wrong type makes the rest of the component
     * unreadable. Dispatch comes last, so no decoder ever has to defend against a message it should
     * never have been given.
     */
    private fun decode(
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
    ): Decode {
        val kind: String? = component["kind"].asStringOrNull()
        if (kind == null) {
            return Decode.Bad(errors.malformed("Component '$id' has no kind", "$pointer/kind", surfaceId, id))
        }
        val schema: A2uiComponentSchema = catalog.component(kind) ?: return Decode.Bad(
            errors.unknownComponent(id, kind, pointer, surfaceId, "is not a component of this catalog"),
        )
        val problem: A2uiError? = checkProperties(component, id, schema, pointer, surfaceId)
        if (problem != null) return Decode.Bad(problem)
        usage.record(kind)
        return decodeFor(kind, component, id, pointer, surfaceId)
    }

    /**
     * The dispatch itself, grouped the way the catalog is grouped.
     *
     * Column, row and list are built inline because there is nothing to check beyond the children
     * the catalog has already verified; everything else has enough shape to earn its own function.
     */
    private fun decodeFor(
        kind: String,
        component: JsonObject,
        id: String,
        pointer: String,
        surfaceId: SurfaceId,
    ): Decode = when (kind) {
        "divider" -> Decode.Ok(UiNode.Divider)

        "text" -> A2uiAtomDecoders.text(component, id, pointer, surfaceId, errors)

        "heading" -> A2uiAtomDecoders.heading(component, id, pointer, surfaceId, errors)

        "badge" -> A2uiAtomDecoders.badge(component, id, pointer, surfaceId, errors)

        "icon" -> A2uiAtomDecoders.icon(component, id, pointer, surfaceId, errors)

        "button" -> A2uiAtomDecoders.button(component, id, pointer, surfaceId, errors)

        "text_field" -> A2uiAtomDecoders.textField(component, id, pointer, surfaceId, errors)

        "checkbox" -> A2uiAtomDecoders.checkbox(component, id, pointer, surfaceId, errors)

        "column" -> Decode.Ok(UiNode.Column(component.refs("children")))

        "row" -> Decode.Ok(UiNode.Row(component.refs("children")))

        "list" -> Decode.Ok(UiNode.ListView(component.refs("children"), component.direction()))

        "card" -> A2uiLayoutDecoders.card(component, id, pointer, surfaceId, errors)

        "modal" -> A2uiLayoutDecoders.modal(component, id, pointer, surfaceId, errors)

        "tabs" -> A2uiLayoutDecoders.tabs(component, id, pointer, surfaceId, errors)

        "task_card" -> A2uiDomainDecoders.taskCard(component, id, pointer, surfaceId, errors)

        "due_date" -> A2uiDomainDecoders.dueDate(component, id, pointer, surfaceId, errors)

        "project_chip" -> A2uiDomainDecoders.projectChip(component, id, pointer, surfaceId, errors)

        else -> Decode.Bad(
            errors.unknownComponent(id, kind, pointer, surfaceId, "is declared but not implemented by this client"),
        )
    }

    /**
     * Presence first, then type and legal values, then the template a string may contain.
     *
     * The order is deliberate: a component missing a required property has nothing worth
     * type-checking, and "label is missing" plus "label is not a string" in one message tells a
     * model less than either alone.
     */
    private fun checkProperties(
        component: JsonObject,
        id: String,
        schema: A2uiComponentSchema,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError? {
        for (property in schema.properties) {
            val problem: A2uiError? = checkProperty(
                component[property.name] ?: JsonNull,
                property,
                id,
                schema,
                pointer,
                surfaceId,
            )
            if (problem != null) return problem
        }
        return checkChildSlot(component, id, schema, pointer, surfaceId)
    }

    /** One declared property against the value the message carries for it. */
    private fun checkProperty(
        value: JsonElement,
        property: A2uiProperty,
        id: String,
        schema: A2uiComponentSchema,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError? {
        if (value is JsonNull) {
            return if (property.required) {
                errors.missingProperty(id, schema.name, property.name, pointer, surfaceId)
            } else {
                null
            }
        }
        return checkValue(value, property, id, schema, pointer, surfaceId)
            ?: checkTemplateIn(value, property, id, schema, pointer, surfaceId)
    }

    /**
     * The container rule is checked after the properties rather than with them.
     *
     * A component whose own `child` property is missing has already been rejected for that; asking
     * again here would put the same mistake in the message twice.
     */
    private fun checkChildSlot(
        component: JsonObject,
        id: String,
        schema: A2uiComponentSchema,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError? {
        val slot: ChildSlot = schema.childSlot
        if (slot !is ChildSlot.ExactlyOne) return null
        if (!component["child"].asStringOrNull().isNullOrBlank()) return null
        return errors.missingProperty(id, schema.name, "child", pointer, surfaceId)
    }

    private fun checkValue(
        value: JsonElement,
        property: A2uiProperty,
        id: String,
        schema: A2uiComponentSchema,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError? {
        if (property.enumValues.isNotEmpty()) {
            val text: String? = value.asStringOrNull()
            return if (text != null && text in property.enumValues) {
                null
            } else {
                errors.mismatch(
                    id,
                    schema.name,
                    property.name,
                    "one of: ${property.enumValues.joinToString(", ")}",
                    pointer,
                    surfaceId,
                    property.enumValues,
                )
            }
        }
        val acceptable: Boolean = when (property.type) {
            A2uiType.STRING, A2uiType.NODE_REF, A2uiType.PATH -> value.asStringOrNull() != null

            A2uiType.INT -> value.asIntOrNull() != null

            A2uiType.BOOL -> value.asBooleanOrNull() != null

            A2uiType.NODE_REFS -> (value as? JsonArray)
                ?.all { it.asStringOrNull() != null } ?: false

            A2uiType.JSON, A2uiType.TONE, A2uiType.DIRECTION -> true
        }
        return if (acceptable) {
            null
        } else {
            errors.mismatch(id, schema.name, property.name, property.type.name.lowercase(), pointer, surfaceId)
        }
    }

    /**
     * Reads a string template now, so that a call to a function this client does not have is
     * reported to the model instead of rendering as an empty string.
     *
     * The *values* are not resolved here and cannot be: the data a template names usually arrives
     * in a later message than the component that shows it. This checks the shape of the claim, not
     * whether the claim is true yet.
     */
    private fun checkTemplateIn(
        value: JsonElement,
        property: A2uiProperty,
        id: String,
        schema: A2uiComponentSchema,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError? {
        val text: String = (value as? JsonPrimitive)?.content
            ?.takeIf { property.type == A2uiType.STRING }
            ?: return null
        if (FormatString.isPlain(text)) return null
        val problem: TemplateError? = FormatString.validate(text, catalog.functions.keys)
        if (problem == null) return null
        return A2uiError(
            code = A2uiErrorCode.INVALID_TEMPLATE,
            message = "'${schema.name}.${property.name}' has an unusable template: ${problem.reason}",
            pointer = "$pointer/${property.name}",
            surfaceId = surfaceId,
            severity = A2uiSeverity.COMPONENT,
            componentId = id,
            allowed = catalog.functions.keys.sorted(),
        )
    }
}
