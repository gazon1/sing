package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared library must not own a Koin entry point.
 *
 * The Koin compiler plugin detects an "aggregator" by matching one of its three
 * entry-point tokens in the raw text of a compilation's sources — comments included —
 * and turns the aggregated module's compile task into a forced, uncacheable re-run.
 * The composition roots live in the launch modules (`:androidApp`, `:desktopApp`,
 * `:mcp-server`), so a marker here means the opt-out in `shared/build.gradle.kts` is
 * hiding a real entry point, not a detector misfire.
 *
 * The scan is comment-stripped, so a KDoc mention cannot fail it; a real call can.
 * The markers are assembled at runtime so this test's own source does not contain
 * them: `NoGlobalKoinInJvmTestsTest` scans `jvmTest` for the literal token.
 */
@Tag("fast")
class KoinEntryPointIsWhereItSaysTest {
    private val markers =
        listOf(
            "start" + "Koin",
            "koin" + "Application",
            "@" + "Koin" + "Application",
        )

    @Test
    fun `no shared production source contains a Koin entry point marker`() {
        val moduleDir = moduleDir()
        val roots =
            listOf(
                moduleDir.resolve("src/commonMain/kotlin"),
                moduleDir.resolve("src/androidMain/kotlin"),
                moduleDir.resolve("src/jvmMain/kotlin"),
            )
        val offenders =
            roots
                .filter { it.exists() }
                .flatMap { root ->
                    root
                        .toFile()
                        .walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".kt") }
                        .filter { containsEntryPoint(KotlinText.stripComments(it.readText())) }
                        .map { it.relativeTo(moduleDir.toFile()).path }
                        .toList()
                }.sorted()
        assertTrue(
            offenders.isEmpty(),
            "These production sources name a Koin entry point. The composition root " +
                "belongs to a launch module, and the compiler plugin's opt-out in " +
                "shared/build.gradle.kts would then hide a real aggregator: $offenders",
        )
    }

    /**
     * A guard whose only observed result is green is not known to work. The control
     * proves the predicate matches inside a body and does not match a longer name.
     */
    @Test
    fun `positive control - the predicate finds a call and ignores a longer name`() {
        val start = markers[0]
        val application = markers[1]
        val annotation = markers[2]
        assertTrue(containsEntryPoint("fun x() { $start { } }"))
        assertTrue(containsEntryPoint("val app = $application { }"))
        assertTrue(containsEntryPoint("$annotation class App"))
        assertFalse(containsEntryPoint("restartTheContainer()"))
        assertFalse(containsEntryPoint("myKoinApplicationBuilder()"))
    }

    /**
     * The opt-out is scoped to `:shared`. If it were copied to a launch module, the
     * app's entry-point graph would stop being re-validated while still looking
     * configured.
     */
    @Test
    fun `the aggregation opt-out is not copied to the launch modules`() {
        val repo = moduleDir().parent ?: error("Could not derive the repository root")
        val offenders =
            listOf("desktopApp", "androidApp", "mcp-server")
                .map { repo.resolve("$it/build.gradle.kts") }
                .filter { it.exists() && it.readText().contains("strictSafetyForceOff") }
                .map { it.parent.fileName.toString() }
        assertTrue(
            offenders.isEmpty(),
            "The Koin aggregation opt-out belongs to :shared only; these launch modules " +
                "must keep the full-graph check: $offenders",
        )
    }

    private fun containsEntryPoint(code: String): Boolean =
        markers.any { marker ->
            Regex("(?<![A-Za-z0-9_])${Regex.escape(marker)}(?![A-Za-z0-9_])").containsMatchIn(code)
        }

    private fun moduleDir(): Path {
        val commonMain =
            System.getProperty("commonMain.root")
                ?: error("commonMain.root system property is not set — see the jvmTest task config")
        return Path
            .of(commonMain)
            .toAbsolutePath()
            .normalize()
            .parent
            ?.parent
            ?.parent
            ?: error("Could not derive the module directory from $commonMain")
    }
}
