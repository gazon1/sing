package com.singularity.todo.feature.genui.engine

import com.singularity.todo.feature.genui.core.A2uiError
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * What one exchange with the model produced.
 *
 * @param text the prose the model wrote around its surface, or an empty string when it wrote
 *   nothing but a surface. Prose is not a failure and is not a fallback: a reply that opens with a
 *   sentence and then gives a surface has both parts, and the sentence is what the user reads first.
 * @param surfaceId the surface that was drawn, or null when none could be built.
 * @param attempts how many model calls were made. More than one means the first was rejected.
 * @param errors everything that was wrong, in the order it was found.
 * @param failed true when the attempt bound was reached without a clean surface. A partially
 *   rendered surface is still left on screen: it is usually more useful than nothing, and the
 *   failure is reported next to it rather than instead of it.
 */
data class GenuiOutcome(
    val text: String,
    val surfaceId: SurfaceId?,
    val attempts: Int,
    val errors: List<A2uiError>,
    val failed: Boolean,
) {
    /** The reasons worth showing a model, de-duplicated and joined into one block. */
    fun feedback(): String = errors
        .distinctBy { it.code to it.componentId }
        .joinToString("\n") { it.asFeedback() }
}
