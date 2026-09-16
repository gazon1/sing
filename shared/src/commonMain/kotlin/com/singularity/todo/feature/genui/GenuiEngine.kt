package com.singularity.todo.feature.genui

import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.feature.genui.transport.GenuiTransport
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates GenuiTransport + A2uiParser + SurfaceController into a single
 * submit(input) → surfaces pipeline.
 *
 * Example:
 * ```
 * val engine = GenuiEngine(transport, parser, controller)
 * engine.submit(surfaceId, "Show me 3 tasks").collect { surfaces -> ... }
 * ```
 *
 * The [surfaces] [Flow][Flow] exposed by [SurfaceController] is what the UI
 * collects to re-render on every state change.
 */
class GenuiEngine(
    private val transport: GenuiTransport,
    private val parser: A2uiParser,
    private val controller: SurfaceController,
) {
    /**
     * Sends [input] to the LLM via [transport], parses each JSON-Lines response
     * with [parser], and applies resulting [UiEvent][com.singularity.todo.feature.genui.parser.UiEvent]s
     * to [controller].
     *
     * The returned [Flow] mirrors [controller.surfaces]; collecting it is
     * equivalent to collecting `controller.surfaces` directly.
     *
     * @param surfaceId target surface for this interaction
     * @param input the user's text prompt
     * @param systemPrompt LLM system prompt (includes BasicCatalog appendix)
     */
    fun submit(
        surfaceId: SurfaceId,
        input: String,
        systemPrompt: String,
    ): Flow<Map<SurfaceId, com.singularity.todo.feature.genui.surface.Surface>> = kotlinx.coroutines.flow.flow {
        // We collect from transport and emit controller.surfaces after each event
        transport.send(input, systemPrompt).collect { line ->
            parser.parseLine(line)?.let { event ->
                controller.apply(event)
            }
        }
        // After the stream ends, emit final state
        emit(controller.surfaces.value)
    }

    /** Direct access to the surfaces state stream. */
    val surfaces get() = controller.surfaces

    /** Resets all surfaces (useful between tests). */
    fun reset() = controller.reset()
}
