package com.singularity.todo.core.log

import co.touchlab.kermit.Logger
import org.junit.Test

/**
 * Verifies that the logging bindings compile and resolve correctly on JVM.
 */
class KoinLoggingGraphTest {

    @Test
    fun `Logger can be resolved from Koin`() {
        val app = org.koin.core.context.startKoin {
            modules(
                org.koin.dsl.module {
                    single { Logger.withTag("TestTag") }
                }
            )
        }
        try {
            app.koin.get<Logger>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    @Test
    fun `LoggerHolder can be resolved from Koin`() {
        val app = org.koin.core.context.startKoin {
            modules(
                org.koin.dsl.module {
                    single { Logger.withTag("App") }
                    single { LoggerHolder(get()) }
                }
            )
        }
        try {
            app.koin.get<LoggerHolder>()
        } finally {
            org.koin.core.context.stopKoin()
        }
    }
}
