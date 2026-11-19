package com.singularity.todo.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * The screen-independent state of one inspected screen.
 *
 * [status] is the screen's own word for where it is: "loaded", "loading" or "error".
 * [task] is the allow-listed, UI-visible payload of the screen's subject, built by the
 * adapter from the fields the screen actually shows and nothing else; null when the screen
 * shows no subject. [focus] is the focused element within that subject, or null when nothing
 * is focused; it carries only what the screen shows, so a title draft is the title an agent
 * reads.
 */
data class ScreenState(
    val status: String,
    val message: String? = null,
    val task: JsonObject? = null,
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
    private val acceptedKeys: Set<String> = (inputSchema["properties"] as? JsonObject)?.keys.orEmpty()

    private val requiredKeys: List<String> =
        (inputSchema["required"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private val propertySchemas: JsonObject? = inputSchema["properties"] as? JsonObject

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
        val text = primitive.content
        val property = propertySchemas?.get(name) as? JsonObject ?: return null
        val minLength = (property["minLength"] as? JsonPrimitive)?.content?.toIntOrNull()
        val pattern = (property["pattern"] as? JsonPrimitive)?.content
        val enumValues = (property["enum"] as? JsonArray)?.map { element -> (element as JsonPrimitive).content }
        return when {
            !primitive.isString -> "'$name' must be a string"

            minLength != null && text.length < minLength -> "'$name' must be at least $minLength characters long"

            pattern != null && Regex(pattern).find(text) == null -> "'$name' must not be blank"

            enumValues != null &&
                enumValues.none {
                    it == text
                }
            -> "'$name' must be one of ${enumValues.joinToString { "'$it'" }}"

            else -> null
        }
    }
}

/**
 * The reusable, agent-facing screen introspection contract.
 *
 * One instance describes one screen: its stable identity, the actions it accepts, and the
 * state-dependent meaning of both. Adapters implement the screen-specific half — which
 * actions exist, what they mean, and how the screen's state maps into [ScreenState] — while
 * the semantics here stay single-sourced.
 */
class ScreenIntrospection(val screenId: String, val actions: List<ScreenAction>) {
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
            state.task?.let { put("task", it) }
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
     * Whether the screen can act on [action] in [state], and what to do with it.
     *
     * The input is checked before anything is dispatched: an unknown action, an action the
     * screen cannot act on right now, or an input that does not fit the action's schema comes
     * back as a failure that names the supported actions and the shape the input had to have.
     *
     * A successful dispatch means the action was **accepted** and handed to the screen — not
     * that the operation it started has completed. The screen does that work asynchronously,
     * so the outcome shows up in the next [snapshot], not in the result of this call.
     */
    fun dispatch(
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
