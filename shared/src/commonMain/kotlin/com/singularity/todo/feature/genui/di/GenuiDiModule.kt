package com.singularity.todo.feature.genui.di

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.core.GenuiUsageCounter
import com.singularity.todo.feature.genui.engine.SurfaceTurnFactory
import com.singularity.todo.feature.genui.function.A2uiFunctionRegistry
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.material3.Material3Catalog
import com.singularity.todo.feature.genui.surface.SurfaceController
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The layer's bindings, in one place.
 *
 * These were duplicated verbatim in the Android and JVM `aiToolsModule` actuals, which is the shape
 * that produces a binding registered twice in one platform and not at all in another. Nothing here
 * is platform-specific: the screen is made from what the model wrote, and the model is asked by
 * the chat's turn, not by this layer.
 */
fun genuiModule(): Module = module {
    // The interface, not the object: everything downstream asks for A2uiCatalog, and a binding
    // registered as the concrete type leaves the graph with a definition nobody can resolve by the
    // type they actually hold. The koin compiler plugin catches this at compile time.
    single<A2uiCatalog> { SingularityCatalog }
    single { A2uiFunctionRegistry() }
    single { A2uiValidator(get()) }
    single { SurfaceController() }
    // The usage tally is the parser's to own: it is the only thing that sees every kind.
    single { A2uiParser(get(), usage = get()) }
    single { A2uiMessageProcessor(get(), get()) }
    single { GenuiRejectionCounter() }
    // One tally for the process, injected into the parser so every turn accumulates into it.
    // The screen is made per answer, so a counter created per answer would answer "what did this
    // answer use" rather than the question the tally exists for: "what does the catalog actually
    // earn".
    single { GenuiUsageCounter() }
    single { ComponentRegistry().also { Material3Catalog.installAll(it) } }
    // The screen of an answer is made per answer; everything it is made of is not.
    factory { SurfaceTurnFactory(get(), get(), get(), get(), get()) }
}
