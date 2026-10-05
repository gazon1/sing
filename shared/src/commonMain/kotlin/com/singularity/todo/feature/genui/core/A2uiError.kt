package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * What a rejection costs.
 *
 * The distinction is the whole point of validating rather than ignoring. Components routinely
 * reference siblings and data that arrive in later messages — that is what a streaming protocol
 * *is* — so discarding a surface because one child has not arrived yet would leave the user with
 * nothing, every time. Equally, a message that cannot be attributed to a usable surface must not
 * be half-applied.
 */
enum class A2uiSeverity {
    /**
     * One component is dropped; the rest of the surface renders.
     *
     * Reserved for violations that are certainly wrong: a component the catalog does not declare,
     * a property that must be there and is not, a child its container may not hold.
     */
    COMPONENT,

    /**
     * Reported, but nothing is dropped.
     *
     * This exists because streaming makes "not yet" indistinguishable from "never": a component
     * that refers to a sibling no message has defined is usually an early reference, not a wrong
     * one, and dropping it would empty a surface that was about to fill in. The model is told
     * either way, which is what lets it correct a genuine mistake.
     */
    ADVISORY,

    /** The whole message is dropped; previously rendered state is left intact. */
    MESSAGE,
}

/**
 * Machine-readable reasons a message was not applied.
 *
 * Every rejection produces one of these. The previous arrangement returned `null` for an unknown
 * operation, an unknown kind, a missing required property and a wrong property type alike, so a
 * model that invented a component and a model that misspelled a property produced the same silence
 * — and the client could not tell the model which one it had got wrong.
 *
 * @param pointer a JSON Pointer into the offending message, e.g. `/components/0/label`. Nullable
 *   because some failures are about the line as a whole.
 * @param componentId the component this rejection takes down, when there is one. Kept as its own
 *   field rather than parsed back out of [pointer] because "which component is lost" is a
 *   different question from "where in the message the problem is": a container is at fault for
 *   holding something it may not hold, but the component that gets dropped is the child.
 * @param allowed the legal values for the thing that was wrong. Only the unknown-component and
 *   disallowed-child reasons populate it, and it is the single most useful thing a client can
 *   send back: a model that invented `Chart` needs the list of names that do exist, not a
 *   restatement of the rule.
 */
data class A2uiError(
    val code: A2uiErrorCode,
    val message: String,
    val pointer: String? = null,
    val surfaceId: SurfaceId? = null,
    val severity: A2uiSeverity = A2uiSeverity.MESSAGE,
    val componentId: String? = null,
    val allowed: List<String> = emptyList(),
) {
    /**
     * The text handed back to the model.
     *
     * Kept short and concrete on purpose: a correction prompt containing twenty stack-trace-shaped
     * lines gets the same reply as one naming the offending component and listing the legal values.
     */
    fun asFeedback(): String = buildString {
        append(code.name)
        val id: String? = componentId
        if (id != null) append(" on component '").append(id).append("'")
        val where: String? = pointer
        if (where != null) append(" at ").append(where)
        append(": ")
        append(message)
        if (allowed.isNotEmpty()) {
            append(" Allowed: ")
            append(allowed.joinToString(", "))
        }
    }
}

/**
 * Every reason a message can be refused.
 *
 * An enum rather than free-form strings because these are read by a model and counted by tests: a
 * typo in a string would produce a reason that is neither of the two.
 */
enum class A2uiErrorCode {
    /** The line is blank, or is not a JSON object at all. */
    MALFORMED_LINE,

    /** The object's single key is not one of the four operations. */
    UNKNOWN_OPERATION,

    /** A message declares a schema version this client does not implement. */
    UNSUPPORTED_SCHEMA_VERSION,

    /** A component names a kind the catalog does not declare. */
    UNKNOWN_COMPONENT,

    /** A component omits a property the catalog marks as required. */
    MISSING_PROPERTY,

    /** A property carries a value of the wrong type, or outside its enumerated values. */
    PROPERTY_TYPE_MISMATCH,

    /** A component appears inside a component that may not contain it. */
    INVALID_CHILD,

    /** A component references an id that no message has defined. */
    UNKNOWN_NODE_REF,

    /** Components reference each other in a cycle. */
    CYCLIC_REFERENCE,

    /** One message defines the same component id more than once. */
    DUPLICATE_COMPONENT_ID,

    /** A creation message names a surface that is already open. */
    SURFACE_ALREADY_EXISTS,

    /** A message names a surface that has not been created. */
    UNKNOWN_SURFACE,

    /** A string template calls a function the catalog does not declare, or is malformed. */
    INVALID_TEMPLATE,

    /** A line exceeded the size bound and was skipped to the next line. */
    LINE_TOO_LONG,

    /** The model provider failed. Not a contract violation; reported to the user, not the model. */
    TRANSPORT_FAILED,
}
