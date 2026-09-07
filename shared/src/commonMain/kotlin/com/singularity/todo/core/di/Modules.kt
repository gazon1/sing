package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.log.LoggerHolder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Logging module — Kermit + KOIN integration.
 */
fun coreLoggingModule(): Module = module {
    factory { Logger.withTag("App") }
    single { LoggerHolder(get()) }
}

/**
 * Returns all domain-level bindings as a KOIN [Module].
 */
fun domainModule(): Module = module {
    includes(
        tasksModule(),
        projectsModule(),
        notesModule(),
        tagsModule(),
        coreModule(),
        aiToolsModule(),
    )
}

/**
 * AI tools, GenUI, and AI use cases — platform-specific.
 * JVM: [jvmAiToolsModule] (uses Koog with real OpenAI).
 * Android: stub (AI features disabled).
 */
expect fun aiToolsModule(): Module
