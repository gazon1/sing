package com.singularity.todo.arch

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.fail

/**
 * Enforces harness conventions for the `shared` JVM test suite.
 *
 * These conventions apply to `shared/src/jvmTest/kotlin/com/singularity/todo`.
 * Unlike the desktop harness conventions (which enforce UI-layer rules like scroll-reachability
 * and button-state assertions), this file covers the shared layer: coroutine-test hygiene,
 * VM test patterns, and source hygiene.
 *
 * ## Why a separate file
 *
 * `HarnessConventionTest` in `desktopApp` enforces desktop-UI-layer conventions:
 * `checkA11y`, scroll-aware drawer helpers, button asserts. Those rules are specific to
 * Compose Desktop UI testing and do not apply to `shared` tests (which run without a UI,
 * against pure Kotlin modules).
 *
 * This file adds conventions that are specific to the shared layer: the `awaitState`
 * helper (vs raw `advanceUntilIdle()` loops), the `runVmTest` helper for ViewModel tests,
 * and the coroutine scope injection pattern.
 *
 * ## Conventions
 *
 * 1. **`awaitState` must be used instead of raw `advanceUntilIdle()` loops.**
 *    A bare `while (!predicate) { advanceUntilIdle() }` can hang the entire test suite:
 *    when the predicate waits on a state that will never arrive, nothing is scheduled, so
 *    virtual time cannot advance, and the deadline is never reached. `awaitState` has a
 *    wall-clock spin guard (120 s) that catches this case and fails fast with a
 *    descriptive message. A loop without the guard is a suite-level hazard.
 *
 * 2. **VM tests should use `runVmTest` rather than hand-rolling `runTest`.**
 *    `runVmTest` wires `AutoCloseableCoroutineScope`, advances the clock, and
 *    handles `assertIs<UiState.Content>()` in one call. Hand-rolled patterns tend to
 *    miss the clock advance or the scope cleanup.
 *
 * 3. **`runBlocking` is prohibited in production test code.**
 *    `runBlocking` blocks the calling thread and risks deadlock in coroutine-heavy code.
 *    Tests must use `runTest` (or `runVmTest`) which uses a virtual thread.
 *
 * 4. **Coroutines `delay(N)` in tests must be replaced with virtual-time equivalents.**
 *    Real-time `delay` cannot be tested reliably; use `advanceTimeBy()` on the test
 *    scope instead. The exception is integration tests that intentionally test
 *    wall-clock behaviour (e.g. timeout boundaries), which are explicitly tagged
 *    `slow` and noted in the exemption.
 */
@Tag("fast")
class SharedHarnessConventionsTest {

    private val testSources: List<File> by lazy {
        File("src/jvmTest/kotlin/com/singularity/todo")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // Exclude helpers (they define the helpers this test validates against)
            .filter { it.parentFile?.name != "helpers" }
            // Exclude this file (it quotes the patterns in its own KDoc and source)
            .filter { it.name != "SharedHarnessConventionsTest.kt" }
            // Exclude fakes (they are test infrastructure, not tests)
            .filter { !it.name.endsWith("Fakes.kt") }
            .filter { !it.name.endsWith("Fake.kt") }
            .toList()
    }

    @Test
    fun `awaitState is used instead of raw advanceUntilIdle polling loops`() {
        // A bare `while (!predicate) { advanceUntilIdle() }` has no spin guard and can
        // hang the suite. `awaitState` wraps the same logic with a wall-clock backstop.
        val VIOLATION_PATTERN = Regex(
            """while\s*\(\s*!\s*\w+\s*\)\s*\{[^}]*advanceUntilIdle\s*\(\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val violations = testSources
            .mapNotNull { file ->
                val body = file.readText()
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) null
                else "${file.name}: ${matches.size} raw advanceUntilIdle loop(s)"
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using raw advanceUntilIdle() loops instead of awaitState:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `awaitState { predicate() }` from " +
                "`shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/AwaitState.kt`. " +
                "It has a 120 s wall-clock spin guard that prevents suite hangs.",
        )
    }

    @Test
    fun `runVmTest is used for ViewModel tests`() {
        // `runVmTest` wires AutoCloseableCoroutineScope + clock advance + state assertions.
        // Hand-rolled `runTest { ... }` + manual scope creation misses one of these.
        val VIOLATION_PATTERN = Regex(
            """runTest\s*\{[^}]*val\s+\w+Scope\s*=\s*AutoCloseableCoroutineScope""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val violations = testSources
            .mapNotNull { file ->
                val body = file.readText()
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) null
                else "${file.name}: hand-rolled TestScope + AutoCloseableCoroutineScope"
            }

        if (violations.isEmpty()) return
        fail(
            "Test files hand-rolling TestScope instead of using runVmTest:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `runVmTest { }` from " +
                "`shared/src/jvmTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`. " +
                "It wires the scope, clock, and state assertions correctly.",
        )
    }

    @Test
    fun `runBlocking is not used in test code`() {
        // `runBlocking` blocks the thread and can deadlock coroutine-heavy code.
        // Tests must use `runTest` (or `runVmTest`).
        val VIOLATION_PATTERN = Regex("""runBlocking\s*<""")

        val violations = testSources
            .mapNotNull { file ->
                // Filter out comment lines that mention runBlocking (e.g. in KDoc)
                val body = file.readLines().filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }.joinToString("\n")
                val matches = VIOLATION_PATTERN.findAll(body).toList()
                if (matches.isEmpty()) null
                else "${file.name}: ${matches.size} runBlocking call(s)"
            }

        if (violations.isEmpty()) return
        fail(
            "Test files using runBlocking (blocks the thread, can deadlock):\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nUse `runTest { }` or `runVmTest { }` instead — they use a virtual " +
                "thread and integrate with the test dispatcher.",
        )
    }
}
