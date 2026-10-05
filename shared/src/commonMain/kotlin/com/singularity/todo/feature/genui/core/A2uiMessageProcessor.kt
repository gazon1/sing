package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId

/** What applying one message actually did. */
data class ApplyResult(
    /** The message as it was applied — with rejected components already removed. */
    val event: UiEvent,
    /** Everything that was wrong with it, for the model and for the log. */
    val errors: List<A2uiError>,
    /** False when the message was rejected whole and nothing was applied. */
    val applied: Boolean,
)

/**
 * The single path by which a message reaches the surfaces.
 *
 * It exists so that "what the model sent", "what passed", and "what is on screen" are decided in
 * one place. The previous path was parse-then-apply with the two halves unaware of each other:
 * the parser returned a message or nothing, and the controller applied whatever it was given, so
 * a message that was partly wrong was either applied whole or dropped whole depending on which
 * half noticed.
 *
 * Order matters and is not interchangeable: parse establishes the envelope, decode checks each
 * component against the catalog, validate checks the relationships between components, and only
 * then does anything reach the surfaces. A component is rejected by the first stage that objects
 * to it, and the model gets that reason.
 */
class A2uiMessageProcessor(private val validator: A2uiValidator, private val controller: SurfaceController) {

    /** Parses, validates and applies one line of model output. */
    fun process(line: String, parser: com.singularity.todo.feature.genui.parser.A2uiParser): A2uiParseOutcome {
        val outcome: A2uiParseOutcome = parser.parseLine(line)
        if (outcome is A2uiParseOutcome.Parsed) {
            apply(outcome.event, outcome.errors)
        }
        return outcome
    }

    /**
     * Validates [event] against the surface it targets and applies what survives.
     *
     * @return the applied event and every reason, so the caller can both render and report.
     */
    fun apply(event: UiEvent, parseErrors: List<A2uiError> = emptyList()): ApplyResult {
        val surfaceId: SurfaceId = event.surfaceId()
        if (!isApplicable(event, surfaceId)) {
            val error: A2uiError = A2uiError(
                code = A2uiErrorCode.UNKNOWN_SURFACE,
                message = "No open surface '$surfaceId'. Send createSurface first.",
                pointer = "/${event.operationName()}/surfaceId",
                surfaceId = surfaceId,
                severity = A2uiSeverity.MESSAGE,
            )
            return ApplyResult(event, parseErrors + error, applied = false)
        }

        val known: Map<String, UiNode> = controller.snapshot(surfaceId)?.components.orEmpty()
        val validated: ValidatedEvent = validator.validate(event, known)
        val errors: List<A2uiError> = parseErrors + validated.errors
        controller.apply(validated.event)
        return ApplyResult(validated.event, errors, applied = true)
    }

    /**
     * Whether the message can be applied to a surface that exists.
     *
     * Creation is exempt — it is what brings the surface into being — and a repeated creation is
     * reported rather than applied, because replacing a surface underneath a model that is still
     * streaming into it leaves the two halves describing different things.
     */
    private fun isApplicable(event: UiEvent, surfaceId: SurfaceId): Boolean = when (event) {
        is UiEvent.CreateSurface -> controller.snapshot(surfaceId) == null
        else -> controller.snapshot(surfaceId) != null
    }
}

private fun UiEvent.surfaceId(): SurfaceId = when (this) {
    is UiEvent.CreateSurface -> surfaceId
    is UiEvent.UpdateComponents -> surfaceId
    is UiEvent.UpdateData -> surfaceId
    is UiEvent.DeleteSurface -> surfaceId
}

private fun UiEvent.operationName(): String = when (this) {
    is UiEvent.CreateSurface -> "createSurface"
    is UiEvent.UpdateComponents -> "updateComponents"
    is UiEvent.UpdateData -> "updateData"
    is UiEvent.DeleteSurface -> "deleteSurface"
}
