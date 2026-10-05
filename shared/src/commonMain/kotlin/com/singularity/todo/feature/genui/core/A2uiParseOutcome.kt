package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.parser.UiEvent

/**
 * What one line of model output turned out to be.
 *
 * The parser used to return `UiEvent?`, and `null` meant five different things: a blank line, a
 * malformed line, an operation it did not know, a component it had never heard of, and a component
 * missing a property it had declared. A caller could not count the first, report the second, or
 * tell a model about the last three — and the correction loop that this layer now needs is
 * impossible to build on an absent value, because the reason is the payload.
 */
sealed interface A2uiParseOutcome {

    /**
     * A well-formed message, ready for validation.
     *
     * [errors] is not empty when some of the message's components were rejected and the rest were
     * not. Partial application and partial reporting travel together for that reason: a surface
     * that rendered nine of ten components has to say something to the model about the tenth, and
     * the two halves cannot be allowed to disagree about whether it happened.
     */
    data class Parsed(val event: UiEvent, val errors: List<A2uiError> = emptyList()) : A2uiParseOutcome

    /**
     * Nothing to apply, and nothing to apologise for: a blank line, or prose the model put around
     * its messages. Kept distinct from [Failed] so a reply of plain text is not reported to the
     * model as a contract violation — which would be the worst possible response to a user who
     * asked a question and got a sentence.
     */
    data class Skipped(val code: A2uiErrorCode, val detail: String) : A2uiParseOutcome

    /** A contract violation, with enough detail to be reported back to the model. */
    data class Failed(val error: A2uiError) : A2uiParseOutcome

    /** The message this outcome carries, or null when there is none. */
    val eventOrNull: UiEvent?
        get() = when (this) {
            is Parsed -> event
            is Skipped -> null
            is Failed -> null
        }

    /** The rejection this outcome carries, or null when there is none. */
    val errorOrNull: A2uiError?
        get() = when (this) {
            is Parsed -> null
            is Skipped -> null
            is Failed -> error
        }
}
