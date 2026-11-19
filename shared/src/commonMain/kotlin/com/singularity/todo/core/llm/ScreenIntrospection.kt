package com.singularity.todo.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The version of the agent-facing screen introspection contract.
 *
 * An adapter implements this contract at [SCREEN_INTROSPECTION_CONTRACT_VERSION]; a driver
 * reads the constant to know which semantics to apply. The version moves only when a
 * published meaning changes.
 */
const val SCREEN_INTROSPECTION_CONTRACT_VERSION: Int = 1

private val SUPPORTED_SCHEMA_KEYS: Set<String> =
    setOf("type", "required", "properties", "additionalProperties")

private val SUPPORTED_PROPERTY_KEYS: Set<String> =
    setOf("type", "minLength", "pattern", "enum")

/**
 * The screen-independent state of one inspected screen.
 *
 * [status] is the screen's own word for where it is: "loaded", "loading" or "error".
 * [subject] is the allow-listed, UI-visible payload of the screen's subject, built by the
 * adapter from the fields the screen actually shows and nothing else; null when the screen
 * shows no subject. [focus] is the focused element within that subject, or null when nothing
 * is focused; it carries only what the screen shows, so a title draft is the title an agent
 * reads.
 */
data class ScreenState(
    val status: String,
    val message: String? = null,
    val subject: JsonObject? = null,
    val focus: JsonObject? = null,
)

/**
 * One named action the screen accepts, with the shape of its input.
 *
 * The input schema is the contract for what an agent may send: it is published by
 * [ScreenIntrospection.describe], checked by [validate] before anything is dispatched, and
 * says the same thing the dispatcher enforces — a property the schema does not define is
 * refused, not ignored, because JSON Schema allows additional properties by default.
 */
class ScreenAction(
    val name: String,
    val summary: String,
    val requirement: String,
    val inputSchema: JsonObject,
    val isAvailable: (ScreenState) -> Boolean = { true },
) {
    private val propertyConstraints: Map<String, StringPropertyConstraint>
    private val acceptedKeys: Set<String>
    private val requiredKeys: List<String>

    init {
        val problem = schemaProblem(inputSchema)
        if (problem != null) {
            throw IllegalArgumentException(problem)
        }
        propertyConstraints = parsePropertyConstraints(inputSchema)
        acceptedKeys = propertyConstraints.keys
        requiredKeys =
            (inputSchema["required"] as? JsonArray)
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
    }

    /**
     * Whether the input matches the published schema exactly.
     *
     * A property the schema does not define is refused, not ignored. Accepting one would
     * let an agent believe a flag it invented took effect, while the screen never saw it.
     * An action whose schema takes no input therefore takes no property at all.
     *
     * The check runs before the action is handed to the screen, so input that does not fit
     * the schema never reaches the screen's own validation.
     */
    internal fun validate(input: JsonObject): Result<Unit> {
        val reason = validationError(input)
        return if (reason == null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException(reason))
        }
    }

    private fun validationError(input: JsonObject): String? {
        val unexpected = input.keys - acceptedKeys
        if (unexpected.isNotEmpty()) {
            return "unexpected propert${if (unexpected.size == 1) "y" else "ies"} ${unexpected.sorted().joinToString {
                "'$it'"
            }}"
        }
        for (name in requiredKeys) {
            if (input[name] == null) {
                return "'$name' is required and must be a non-empty string"
            }
        }
        for (name in input.keys.sorted()) {
            val reason = fieldError(input, name)
            if (reason != null) {
                return reason
            }
        }
        return null
    }

    private fun fieldError(
        input: JsonObject,
        name: String,
    ): String? {
        val value = input[name] ?: return null
        val primitive = value as? JsonPrimitive ?: return "'$name' must be a string"
        if (!primitive.isString) {
            return "'$name' must be a string"
        }
        val constraint = propertyConstraints[name] ?: return null
        val text = primitive.content
        return when {
            constraint.minLength != null && text.length < constraint.minLength -> {
                "'$name' must be at least ${constraint.minLength} characters long"
            }

            constraint.pattern != null && constraint.pattern.find(text) == null -> {
                "'$name' does not match the required pattern"
            }

            constraint.enumValues != null && constraint.enumValues.none { it == text } -> {
                "'$name' must be one of ${constraint.enumValues.joinToString { "'$it'" }}"
            }

            else -> {
                null
            }
        }
    }

    private fun schemaProblem(schema: JsonObject): String? {
        val unsupported = schema.keys - SUPPORTED_SCHEMA_KEYS
        if (unsupported.isNotEmpty()) {
            return "Unsupported schema keyword(s): ${unsupported.sorted().joinToString { "'$it'" }}"
        }
        if (schema["type"]?.jsonPrimitive?.content != "object") {
            return "The introspection schema must be of type \"object\"."
        }
        if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull != false) {
            return "The introspection schema must set additionalProperties to false."
        }
        val properties = schema["properties"] as? JsonObject
        val badProperty: String? =
            properties?.let { props ->
                props.keys.firstNotNullOfOrNull { name: String -> propertyProblem(name, props[name]) }
            }
        val badRequired = requiredProblem(schema, properties)
        return badProperty ?: badRequired
    }

    private fun requiredProblem(
        schema: JsonObject,
        properties: JsonObject?,
    ): String? {
        val required = schema["required"] as? JsonArray
        val names: List<String> =
            required?.map { element: JsonElement ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return "The introspection schema must set required to an array of strings."
            } ?: return "The introspection schema must set required to an array of strings."
        if (names.toSet().size != names.size) {
            return "The introspection schema must not repeat a required property."
        }
        if (properties == null) {
            return "The introspection schema must set properties."
        }
        val missing = names.filter { it !in properties.keys }
        return if (missing.isNotEmpty()) {
            "The introspection schema must declare the required properties: ${missing.sorted().joinToString {
                "'$it'"
            }}"
        } else {
            null
        }
    }

    private fun propertyProblem(
        name: String,
        schema: JsonElement?,
    ): String? {
        val property = schema as? JsonObject ?: return "'$name' must be a JSON Schema object."
        val unsupported = property.keys - SUPPORTED_PROPERTY_KEYS
        if (unsupported.isNotEmpty()) {
            return "Unsupported schema keyword(s) for '$name': ${unsupported.sorted().joinToString { "'$it'" }}"
        }
        val typeContent = (property["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (typeContent != "string") {
            return "The introspection schema for '$name' must be of type \"string\"."
        }
        val minLengthPrimitive = property["minLength"] as? JsonPrimitive
        val minLength = minLengthPrimitive?.takeIf { !it.isString }?.content?.toIntOrNull()
        val minLengthProblem =
            if (property.containsKey("minLength") && (minLength == null || minLength < 0)) {
                "The introspection schema for '$name' must set a non-negative minLength."
            } else {
                null
            }
        val patternContent = (property["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val regex = patternContent?.let { runCatching { Regex(it) }.getOrNull() }
        val patternProblem =
            if (property.containsKey("pattern") && (patternContent == null || regex == null)) {
                "The introspection schema for '$name' must set a pattern that is a valid regex."
            } else {
                null
            }
        val enumProblem =
            when (val enumElement = property["enum"]) {
                null -> {
                    null
                }

                is JsonArray -> {
                    if (enumElement.any { element -> element !is JsonPrimitive || !element.isString }) {
                        "The introspection schema for '$name' must set enum to strings only."
                    } else {
                        null
                    }
                }

                else -> {
                    "The introspection schema for '$name' must set enum to strings only."
                }
            }
        return minLengthProblem ?: patternProblem ?: enumProblem
    }

    private fun parsePropertyConstraints(schema: JsonObject): Map<String, StringPropertyConstraint> {
        val properties = schema["properties"] as? JsonObject ?: return emptyMap()
        return properties.mapValues { (_, element) ->
            val property = element as JsonObject
            val enumValues =
                (property["enum"] as? JsonArray)?.map { enumElement ->
                    (enumElement as JsonPrimitive).content
                }
            StringPropertyConstraint(
                minLength = (property["minLength"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull(),
                pattern = (property["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::Regex),
                enumValues = enumValues,
            )
        }
    }

    private data class StringPropertyConstraint(
        val minLength: Int?,
        val pattern: Regex?,
        val enumValues: List<String>?,
    )
}

/**
 * The reusable, agent-facing screen introspection contract.
 *
 * One instance describes one screen: its stable identity, the actions it accepts, and the
 * state-dependent meaning of both. Adapters implement the screen-specific half — which
 * actions exist, what they mean, and how the screen's state maps into [ScreenState] — while
 * the semantics here stay single-sourced.
 */
class ScreenIntrospection(val screenId: String, actions: List<ScreenAction>) {
    val actions: List<ScreenAction> = actions.toList()

    init {
        val names = this.actions.map { it.name }
        if (names.toSet().size != names.size) {
            throw IllegalArgumentException("The screen '$screenId' must not declare two actions with the same name.")
        }
    }

    /**
     * What the screen shows, as an allow-listed JSON object.
     *
     * The fields are the ones a reader needs to understand the screen. The domain graph
     * behind them is not serialized: no credentials, no profile data, and no property of the
     * subject that the screen does not show.
     */
    fun snapshot(state: ScreenState): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("status", state.status)
            state.message?.let { put("message", it) }
            state.subject?.let { put("subject", it) }
        }

    /**
     * The actions the screen accepts, each with its input shape and whether it can be
     * acted on right now.
     */
    fun describe(state: ScreenState): JsonObject =
        buildJsonObject {
            put("screen", screenId)
            put("status", state.status)
            put(
                "actions",
                JsonArray(
                    actions.map { action ->
                        buildJsonObject {
                            put("name", action.name)
                            put("summary", action.summary)
                            put("requirement", action.requirement)
                            put("available", action.isAvailable(state))
                            put("input", action.inputSchema)
                        }
                    },
                ),
            )
            state.focus?.let { put("focus", it) }
        }

    /**
     * Whether the screen can act on [action] in [state] and whether [input] fits the action's
     * schema.
     *
     * The input is checked before anything is dispatched: an unknown action, an action the
     * screen cannot act on right now, or an input that does not fit the action's schema comes
     * back as a failure that names the supported actions and the shape the input had to have.
     *
     * A successful validation is what the adapter's actual dispatch waits for; it does not
     * perform the action. The adapter hands an accepted action to the screen through its own
     * action boundary, and the outcome shows up in the next [snapshot], not in this result.
     */
    fun validate(
        state: ScreenState,
        action: String,
        input: JsonObject,
    ): Result<Unit> {
        val spec =
            actions.firstOrNull { it.name == action }
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Unknown action '$action'. Supported actions: ${actions.joinToString { it.name }}",
                    ),
                )
        if (!spec.isAvailable(state)) {
            return Result.failure(
                IllegalArgumentException(
                    "Action '${spec.name}' is not available right now: ${spec.requirement} " +
                        "(the screen is '${state.status}'). " +
                        "Supported actions: ${actions.joinToString { it.name }}",
                ),
            )
        }
        return spec.validate(input).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                Result.failure(
                    IllegalArgumentException(
                        "Action '${spec.name}' rejected the input: ${error.message}. " +
                            "Expected input: ${spec.inputSchema}. " +
                            "Supported actions: ${actions.joinToString { it.name }}",
                    ),
                )
            },
        )
    }
}
