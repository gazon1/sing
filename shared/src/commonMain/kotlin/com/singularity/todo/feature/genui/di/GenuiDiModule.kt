package com.singularity.todo.feature.genui.di

import com.singularity.todo.feature.genui.catalog.A2uiCatalog
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.core.GenuiUsageCounter
import com.singularity.todo.feature.genui.engine.GenuiSession
import com.singularity.todo.feature.genui.function.A2uiFunctionRegistry
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.material3.Material3Catalog
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.transport.BaseGenuiTransport
import com.singularity.todo.feature.genui.transport.GenuiTransport
import com.singularity.todo.feature.genui.transport.UsageRecordingGenuiTransport
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The layer's bindings, in one place.
 *
 * These were duplicated verbatim in the Android and JVM `aiToolsModule` actuals, which is the shape
 * that produces a binding registered twice in one platform and not at all in another. Nothing here
 * is platform-specific: the transport is supplied by the caller as [genuiTransport], so a platform
 * that has no model provider — or a test — substitutes its own without the graph growing a branch.
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
    // `GenuiSession` is a factory, so a counter created per session would answer "what did this
    // conversation use" rather than the question the tally exists for: "what does the catalog
    // actually earn".
    single { GenuiUsageCounter() }
    single { ComponentRegistry().also { Material3Catalog.installAll(it) } }
    single<GenuiTransport> {
        UsageRecordingGenuiTransport(get<BaseGenuiTransport>(), get(), get(), get())
    }
    factory {
        GenuiSession(
            get(),
            get(),
            get(),
            get(),
            get(),
            rejectionCounter = get(),
        )
    }
}
