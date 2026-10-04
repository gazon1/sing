package com.singularity.todo

import com.singularity.todo.core.di.platformModule
import com.singularity.todo.test.helpers.testPlatformModule
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every DAO the production [platformModule] binds must also be bound by
 * [testPlatformModule] — the desktop flow harness builds its Koin graph from the
 * test module, and a missing DAO surfaces as `NoDefinitionFoundException` thrown
 * INSIDE composition, which Compose retries every frame: an endless redraw loop
 * that looks like a hang, not an error.
 *
 * This exact failure shipped with the ai-proposal merge: `TestPlatformModule` was
 * never taught about `TimeEntryDao`/`ProposalDao`/`ProposalItemDao`. The guard
 * compares definition REGISTRATION only — nothing is resolved, so no real
 * database, DataStore file, or OS port is touched.
 */
@Tag("fast")
class TestPlatformModuleParityTest {

    @Test
    fun `production DAO bindings all exist in the test platform module`() {
        val production = daoTypes(listOf(platformModule()))
        val test = daoTypes(listOf(testPlatformModule()))
        val missing = production - test
        assertTrue(
            missing.isEmpty(),
            "testPlatformModule is missing DAO bindings that platformModule binds: $missing",
        )
    }

    @OptIn(KoinInternalApi::class)
    private fun daoTypes(moduleList: List<Module>): Set<String> {
        val app = koinApplication { modules(moduleList) }
        try {
            return app.koin.instanceRegistry.instances.values
                .mapNotNull { it.beanDefinition.primaryType.simpleName }
                .filterTo(mutableSetOf()) { it.endsWith("Dao") }
        } finally {
            app.close()
        }
    }
}
