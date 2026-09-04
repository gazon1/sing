package com.singularity.todo.core.di

import org.junit.Test
import org.koin.test.check.checkModules

/**
 * Verifies the core DI graph on JVM via `checkModules()`.
 *
 * `checkModules()` walks the entire graph and fails fast if any binding is missing —
 * before the app ever reaches a device. This catches problems like missing
 * `AttachmentStorage` or unregistered `BackupRepository` that previously only
 * appeared as `NoDefinitionFoundException` at runtime on the device.
 *
 * The `coreDomainModule` (repositories, use cases, ViewModels) is tested here.
 * `platformModule` (database, ports) is implicitly verified by being passed to checkModules().
 *
 * AI tools (Koog/GenUI) are JVM-only and require a real OpenAI API key —
 * they are validated in AI-specific integration tests.
 *
 * Run with: ./gradlew :shared:jvmTest --tests "com.singularity.todo.core.di.DiGraphTest"
 */
class DiGraphTest {

    @Suppress("DEPRECATION")
    @Test
    fun `core domain graph verifies on JVM`() {
        checkModules {
            modules(coreDomainModule(), platformModule())
        }
    }

    @Test
    fun `AI tools require API key — validated by integration test`() {
        // Placeholder: AI tools (OpenAI client) need a real API key — checkModules()
        // would throw ExceptionInInitializerError from OpenAILLMClient("") during singleton creation.
    }
}
