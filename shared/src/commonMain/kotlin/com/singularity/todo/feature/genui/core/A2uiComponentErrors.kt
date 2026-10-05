package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * The rejections this layer reports, built in one place.
 *
 * Every message a model is shown about its own output passes through here, which is what keeps the
 * shape consistent: the same code, the same JSON Pointer convention, and — for the two rejections
 * where it matters most — the list of things that *are* legal attached to the error.
 *
 * Held apart from the factory and the decoders because a builder is not a decoder: it carries no
 * knowledge of any component, and keeping them together is what made the decoder file the largest
 * in the layer by a wide margin.
 */
internal class A2uiComponentErrors(private val catalog: A2uiCatalog) {

    /**
     * A kind this client does not draw.
     *
     * [because] distinguishes the two ways that happens — the catalog never declared it, or it
     * declared it and this client has no decoder — because they need different fixes, and a model
     * can only fix one of them by choosing a different name.
     *
     * The legal names travel with the error. They are the one piece of information the model cannot
     * reconstruct: it produced an invented word, and being told "that is not a component" without
     * a list is a rejection it can only answer by inventing another word.
     */
    fun unknownComponent(
        id: String,
        kind: String,
        pointer: String,
        surfaceId: SurfaceId,
        because: String,
    ): A2uiError = A2uiError(
        code = A2uiErrorCode.UNKNOWN_COMPONENT,
        message = "'$kind' $because.",
        pointer = "$pointer/kind",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = id,
        allowed = catalog.components.keys.sorted(),
    )

    fun missingProperty(
        id: String,
        component: String,
        property: String,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError = A2uiError(
        code = A2uiErrorCode.MISSING_PROPERTY,
        message = "'$component' requires '$property'.",
        pointer = "$pointer/$property",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = id,
    )

    /**
     * "Needs one of these two."
     *
     * A different message from [missingProperty] on purpose: telling a model that `due_date`
     * requires `path` when it also accepts `value` teaches it a rule that is not true, and the next
     * answer it sends will be confidently wrong in a new way.
     */
    fun eitherProperty(
        id: String,
        component: String,
        first: String,
        second: String,
        pointer: String,
        surfaceId: SurfaceId,
    ): A2uiError = A2uiError(
        code = A2uiErrorCode.MISSING_PROPERTY,
        message = "'$component' needs either '$first' or '$second'.",
        pointer = "$pointer/$first",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = id,
    )

    fun mismatch(
        id: String,
        component: String,
        property: String,
        expected: String,
        pointer: String,
        surfaceId: SurfaceId,
        allowed: List<String> = emptyList(),
    ): A2uiError = A2uiError(
        code = A2uiErrorCode.PROPERTY_TYPE_MISMATCH,
        message = "'$component.$property' must be $expected.",
        pointer = "$pointer/$property",
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = id,
        allowed = allowed,
    )

    fun malformed(
        detail: String,
        pointer: String? = null,
        surfaceId: SurfaceId? = null,
        componentId: String? = null,
    ): A2uiError = A2uiError(
        code = A2uiErrorCode.MALFORMED_LINE,
        message = detail,
        pointer = pointer,
        surfaceId = surfaceId,
        severity = A2uiSeverity.COMPONENT,
        componentId = componentId,
    )
}
