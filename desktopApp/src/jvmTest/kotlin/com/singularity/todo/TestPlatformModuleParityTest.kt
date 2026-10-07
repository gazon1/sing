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

    /**
     * Every non-DAO definition the production JVM module binds must also be bound by
     * [testPlatformModule], under the same type.
     *
     * ## Why the DAO check above was not enough
     *
     * It compares only names ending in `Dao`, and it was written after the DAO half of
     * exactly this defect shipped. The same substitution broke a *second* class of
     * definition on 2026-10-07: `SettingsViewModel` takes `LogBundleExporter` and
     * `FileSharePort`, neither of which `testPlatformModule` bound, so opening Settings
     * from any flow test threw
     *
     * ```
     * InstanceCreationException: Could not create instance for
     * '[Factory: 'com.singularity.todo.feature.settings.SettingsViewModel']'
     * ```
     *
     * Compose retries a composition failure every frame, so the surface presents as an
     * endless redraw loop that looks like a hang rather than an error — thirty minutes
     * of CPU in `RenderNode_nDrawInto` before it was stopped, and no exception the test
     * could catch. That is why no desktop flow test had ever opened Settings: the
     * screen was unreachable and nothing said so.
     *
     * ## What is compared, and what is not
     *
     * Registration only, exactly as the DAO check does — nothing is resolved, so no
     * database, DataStore file, or OS port is touched and the test stays `fast`.
     *
     * `String` is excluded because the two modules legitimately bind different ones:
     * production binds the backups directory *and* the logs directory, while the test
     * module binds only a temp backups path and passes the logs path directly to the
     * `LogBundleExporter` constructor. Collapsing that to "one `String`" would report a
     * difference that is not one, and a parity check that cries wolf gets deleted.
     *
     * The check is a **superset** guard, so a definition the test module binds on purpose
     * without a production counterpart (`RemoteConfigPort`, `Haptic`) is fine — it only
     * fails when production has a type the test module lacks.
     */
    @Test
    fun `production port bindings all exist in the test platform module`() {
        val production = portTypes(listOf(platformModule()))
        val test = portTypes(listOf(testPlatformModule()))
        val missing = production - test
        assertTrue(
            missing.isEmpty(),
            "testPlatformModule is missing port bindings that platformModule binds: $missing\n" +
                "A missing definition throws INSIDE composition, which Compose retries every " +
                "frame — the symptom is an endless redraw that looks like a hang, not a " +
                "test failure. Add the binding to testPlatformModule.",
        )
    }

    @OptIn(KoinInternalApi::class)
    private fun daoTypes(moduleList: List<Module>): Set<String> =
        definedTypes(moduleList).filterTo(mutableSetOf()) { it.endsWith("Dao") }

    @OptIn(KoinInternalApi::class)
    private fun portTypes(moduleList: List<Module>): Set<String> =
        definedTypes(moduleList).filterTo(mutableSetOf()) { it != "String" }

    @OptIn(KoinInternalApi::class)
    private fun definedTypes(moduleList: List<Module>): Set<String> {
        val app = koinApplication { modules(moduleList) }
        try {
            return app.koin.instanceRegistry.instances.values
                .mapNotNull { it.beanDefinition.primaryType.simpleName }
                .toMutableSet()
        } finally {
            app.close()
        }
    }
}
