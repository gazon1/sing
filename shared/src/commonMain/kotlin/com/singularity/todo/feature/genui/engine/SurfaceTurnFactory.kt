package com.singularity.todo.feature.genui.engine

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * Makes a screen for an answer.
 *
 * A screen is identified by the answer that drew it, so the identifier cannot be chosen by the
 * screen itself — it is given. That is the whole reason this type exists: the parser, the
 * processor and the controller are process-wide, and what is per answer is the identifier and
 * the framer that goes with it.
 */
class SurfaceTurnFactory(
    private val parser: A2uiParser,
    private val processor: A2uiMessageProcessor,
    private val controller: SurfaceController,
    private val catalog: A2uiCatalog,
    private val rejectionCounter: GenuiRejectionCounter,
) {
    /** Starts the screen of one answer. */
    fun begin(id: SurfaceId): SurfaceTurn =
        SurfaceTurn(id, parser, processor, controller, catalog, rejectionCounter)
}
